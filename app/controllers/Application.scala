package controllers

//import play.api._;
import play.api.Logger;
//import play.api.mvc._;
import play.api.mvc.Controller;
import play.api.mvc.Action;
import play.api.mvc.AnyContent;
import play.api.mvc.Request;
import play.api.mvc.Result;
import play.api.data.Form;
//import play.api.data.Forms._;
import play.api.data.Forms.mapping;
import play.api.data.Forms.number;

import play.api.Play.current;
import play.api.libs.concurrent.Akka;
import play.api.libs.json.Json.toJson;
import play.api.libs.json.JsValue;

//import collection.JavaConversions._;
import collection.JavaConversions.mapAsScalaMap;
import collection.JavaConversions.asScalaBuffer;

import java.io.ByteArrayInputStream;
import java.util.Calendar;
import java.util.Locale;
import java.util.TimeZone;
import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.ObjectListing;
import com.amazonaws.services.s3.model.ObjectMetadata;

import org.apache.commons.codec.binary.Base64;

import jp.co.flect.papertrail.LogAnalyzer;
import jp.co.flect.papertrail.Counter;
import jp.co.flect.net.IPFilter;

import models.JqGrid;
import models.CacheManager;
import models.CacheManager.CacheStatus;
import models.CacheManager.Summary;
import models.CacheManager.DateKey;

object Application extends Controller {
	
	//初期設定
	sys.env.get("LOCALE").foreach { str =>
		Locale.setDefault(str.split("_").toList match {
			case lang :: country :: variant :: Nil => new Locale(lang, country, variant);
			case lang :: country :: Nil => new Locale(lang, country);
			case lang :: Nil => new Locale(lang);
			case _ => Locale.getDefault;
		});
	};
	
	sys.env.get("TIMEZONE").foreach { str =>
		TimeZone.setDefault(TimeZone.getTimeZone(str));
	};
	
	private val ACCESS_KEY = sys.env("S3_ACCESSKEY");
	private val SECRET_KEY = sys.env("S3_SECRETKEY");
	private val ARCHIVES = sys.env.filterKeys(_.startsWith("PAPERTRAIL_ARCHIVE_"))
		.map{ case(key, value) =>
			val newKey = key.substring("PAPERTRAIL_ARCHIVE_".length).toLowerCase;
			val (bucket, dir) = value.span(_ != '/');
			
			val newValue = ArchiveInfo(newKey, bucket,
				if (dir.isEmpty) "papertrail/logs" else dir.substring(1)
			);
			(newKey, newValue);
		};
	
	private val IP_FILTER = sys.env.get("ALLOWED_IP")
		.map(IPFilter.getInstance(_));
	
	private val BASIC_AUTH = sys.env.get("BASIC_AUTHENTICATION")
		.filter(_.split(":").length == 2)
		.map { str =>
			val strs = str.split(":");
			(strs(0), strs(1));
		};
	
	private def filterAction(f: Request[AnyContent] => Result): Action[AnyContent] = Action {request =>
		def ipFilter = {
			IP_FILTER match {
				case Some(filter) =>
					val ip = request.headers.get("x-forwarded-for").getOrElse(request.remoteAddress);
					filter.allow(ip);
				case None =>
					true;
			}
		}
		def basicAuth = {
			BASIC_AUTH match {
				case Some((username, password)) =>
					request.headers.get("Authorization").map { auth =>
						auth.split(" ").drop(1).headOption.map { encoded =>
							new String(Base64.decodeBase64(encoded.getBytes)).split(":").toList match {
								case u :: p :: Nil => u == username && password == p;
								case _ => false;
							}
						}.getOrElse(false);
					}.getOrElse {
						false;
					}
				case None =>
					true;
			}
		}
		val t = System.currentTimeMillis();
		try {
			if (!ipFilter) {
				Forbidden;
			} else if (!basicAuth) {
			    Unauthorized.withHeaders("WWW-Authenticate" -> "Basic realm=\"Secured\"");
			} else {
				f(request);
			}
		} finally {
			Logger.info(request.path + " (" + (System.currentTimeMillis() - t) + "ms)");
		}
	}
	
	private def bucketCheck(name: String)(f: ArchiveInfo => Result) = {
		ARCHIVES.get(name).map(f(_)).getOrElse(NotFound);
	}
	
	private val dateForm = Form(mapping(
		"year" -> number,
		"month" -> number,
		"date" -> number
	)(DateKey.apply)(DateKey.unapply));
	
	def index = filterAction { request =>
		Ok(views.html.index(ARCHIVES.keySet));
	}
	
	def calendar(name: String) = filterAction { request =>
		bucketCheck(name) { info =>
			val offset = TimeZone.getDefault().getRawOffset() / (60 * 60 * 1000);
			Ok(views.html.calendar(name, offset));
		}
	}
	
	def logcount(name: String) = jqGridData(name, Counter.Type.Count);
	def responsetime(name: String) = jqGridData(name, Counter.Type.Time);
	
	private def jqGridData(name: String, counterType: Counter.Type) = filterAction { implicit request =>
		bucketCheck(name) { info =>
			val key = dateForm.bindFromRequest;
			if (key.hasErrors) {
				Ok(toJson(JqGrid.empty));
			} else {
				val summary = CacheManager(info.name).get(key.get);
				summary.status match {
					case CacheStatus.Ready =>
						Ok(toJson(JqGrid.data(summary.csv(counterType))));
					case _ =>
						NotFound;
				}
			}
		}
	}
	
	def show(name: String, year: Int, month: Int, date: Int) = filterAction { implicit request =>
		bucketCheck(name) { info =>
			val key = DateKey(year, month, date);
			val summary = CacheManager(info.name).get(key);
			summary.status match {
				case CacheStatus.Ready =>
					Ok(summary.fullcsv);
				case _ =>
					NotFound;
			}
		}
	}
	
	def status(name: String) = filterAction { implicit request =>
		bucketCheck(name) { info =>
			val man = CacheManager(info.name);
			val key = dateForm.bindFromRequest.get;
			val summary = man.get(key);
			summary.status match {
				case CacheStatus.Unprocessed =>
					try {
						checkS3(info, key);
					} catch {
						case e: Exception => 
							e.printStackTrace();
							Ok(CacheStatus.Error.toString);
					}
				case CacheStatus.Found | CacheStatus.Ready =>
					Ok(summary.status.toString);
				case CacheStatus.Error =>
					man.remove(key);
					Ok(summary.status.toString);
				case CacheStatus.NotFound =>
					throw new IllegalStateException();
			}
		}
	}
	
	private def checkS3(info: ArchiveInfo, key: DateKey) = {
		val client = new AmazonS3Client(new BasicAWSCredentials(ACCESS_KEY, SECRET_KEY));
		val list = client.listObjects(info.bucket, info.directory + key.toDirectory);
		if (list.getObjectSummaries() == null || list.getObjectSummaries().size() == 0) {
			Ok(CacheStatus.NotFound.toString);
		} else {
			val summary = new Summary(CacheStatus.Found, null, null);
			CacheManager(info.name).put(key, summary);
			Akka.future {
				processCSV(client, info, list, key);
			}
			Ok(CacheStatus.Found.toString);
		}
	}
	
	private def processCSV(client: AmazonS3Client, info: ArchiveInfo, list: ObjectListing, key: DateKey) = {
		if (list.getObjectSummaries().exists { obj =>
			obj.getKey().endsWith("/summary.csv");
		}) {
			downloadCSV(client, info, key);
		} else {
			try {
				prepareCSV(client, info, key);
			} catch {
				case e: Exception => 
					e.printStackTrace();
					CacheManager(info.name).put(key, Summary(CacheStatus.Error));
			}
		}
	}
	
	private def downloadCSV(client: AmazonS3Client, info: ArchiveInfo, key: DateKey) = {
	}
	
	private def prepareCSV(client: AmazonS3Client, info: ArchiveInfo, key: DateKey) = {
		val args = Array(
			"-al",
			"-ac",
			"-sl", "1000",
			"-sc", "20",
			//"-rp",
			"-ce",
			"-se",
			"-ds",
			"-pg",
			//"-pt",
			//"-rg",
			"-he",
			"-rt",
				"/e-ink/product/\\d+",
				"/e-ink/select/author/\\d+",
				"/e-ink/select/genre/\\d+",
				"/e-ink/select/genre/\\d+/\\d+",
				"/mobile/product/\\d+",
				"/mobile/select/author/\\d+",
				"/mobile/select/genre/\\d+",
				"/mobile/select/genre/\\d+/\\d+",
				"/ebook/detail/[^/]+",
			//"-rn",
			"-ct",
			"-s3", ACCESS_KEY, SECRET_KEY, info.bucket, info.directory, key.toDateStr
		);
		val analyzer = LogAnalyzer.process(args);
		val countCsv = analyzer.toString(Counter.Type.Count);
		val timeCsv = analyzer.toString(Counter.Type.Time);
		val summary = Summary(CacheStatus.Ready, countCsv, timeCsv);
		/*
		val data = summary.fullcsv.getBytes("utf-8");
		val meta = new ObjectMetadata();
		meta.setContentLength(data.length);
		client.putObject(Bucket, Base + key + "/summary.csv", 
			new ByteArrayInputStream(data),
			meta);
		*/
		CacheManager(info.name).put(key, summary);
	}
	
	case class ArchiveInfo(name: String, bucket: String, directory: String);
	
}