package controllers

import play.api._;
import play.api.mvc._;
import play.api.data.Form;
import play.api.data.Forms._;
import play.api.Play.current;
import play.api.libs.concurrent.Akka;
import play.api.libs.json.Json.toJson;
import play.api.libs.json.JsValue;

import collection.JavaConversions._;

import java.io.ByteArrayInputStream;
import java.util.Calendar;
import java.util.Locale;
import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.ObjectListing;
import com.amazonaws.services.s3.model.ObjectMetadata;

import jp.co.flect.papertrail.LogAnalyzer;
import jp.co.flect.papertrail.Counter;
import jp.co.flect.net.IPFilter;

import models.JqGrid;
import models.CacheManager;
import models.CacheManager.CacheStatus;
import models.CacheManager.Summary;
import models.CacheManager.DateKey;

object Application extends Controller {
	
	if (System.getenv("LOCALE") != null) {
		val strs = System.getenv("LOCALE").split("_");
		val l = strs.length match {
			case 1 => new Locale(strs(0));
			case 2 => new Locale(strs(0), strs(1));
			case 3 => new Locale(strs(0), strs(1), strs(2));
			case _ => Locale.getDefault();
		}
		Locale.setDefault(l);
	}
	
	private val ipFilter = if (System.getenv("ALLOWED_IP") == null) {
		None;
	} else {
		Some(IPFilter.getInstance(System.getenv("ALLOWED_IP")));
	}
	
	private def filterAction(f: Request[AnyContent] => Result): Action[AnyContent] = Action {request =>
		ipFilter match {
			case Some(filter) =>
				val ip = request.headers.get("x-forwarded-for").getOrElse(request.remoteAddress);
				if (filter.allow(ip))
					f(request);
				else
					Forbidden;
			case None =>
				f(request);
		}
	}
	
	private def bucketCheck(name: String)(f: ArchiveInfo => Result) = {
		Buckets.get(name) match {
			case Some(info) => f(info);
			case None => NotFound;
		}
	}
	
	private val AccessKey = System.getenv("S3_ACCESSKEY");
	private val SecretKey = System.getenv("S3_SECRETKEY");
	private val Buckets = System.getenv()
		.filterKeys(_.startsWith("PAPERTRAIL_ARCHIVE_"))
		.map{ case(key, value) =>
			val valueSep = value.indexOf('/');
			val newKey = key.substring("PAPERTRAIL_ARCHIVE_".length).toLowerCase;
			val newValue = if (valueSep == -1) {
				ArchiveInfo(newKey, value, "papertrail/logs");
			} else {
				ArchiveInfo(newKey, value.substring(0, valueSep), value.substring(valueSep+1));
			}
			(newKey, newValue);
		};
	
	private val dateForm = Form(mapping(
		"year" -> number,
		"month" -> number,
		"date" -> number
	)(DateKey.apply)(DateKey.unapply));
	
	def index = filterAction { request =>
		Ok(views.html.index(Buckets.keySet));
	}
	
	def calendar(name: String) = filterAction { request =>
		Buckets.get(name) match {
			case Some(v) => Ok(views.html.calendar(name));
			case _ => NotFound;
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
					checkS3(info, key);
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
		val client = new AmazonS3Client(new BasicAWSCredentials(AccessKey, SecretKey));
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
			"-s3", AccessKey, SecretKey, info.bucket, info.directory, key.toDateStr
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