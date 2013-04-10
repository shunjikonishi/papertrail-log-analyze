package controllers

import play.api._;
import play.api.mvc._;
import play.api.data.Form;
import play.api.data.Forms._;
import play.api.cache.Cache;
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
import jp.co.flect.net.IPFilter;

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
	
	case class DateKey(year: Int, month: Int, date: Int) {
		def toDateStr = {
			"%d-%02d-%02d".format(year, month, date);
		}
		def toDirectory = {
			"/dt=%d-%02d-%02d".format(year, month, date);
		}
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
	
	def logcount(name: String) = filterAction { implicit request =>
		Buckets.get(name) match {
			case Some(info) => 
				val key = dateForm.bindFromRequest;
				if (key.hasErrors) {
					Ok(toJson(jqGrid.empty));
				} else {
					val csv = info.getCache(key.get);
					csv match {
						case Some(str) => 
							Ok(toJson(jqGrid.data(str)));
						case None =>
							NotFound;
					}
				}
			case _ => NotFound;
		}
	}
	
	def show(name: String, year: Int, month: Int, date: Int) = filterAction { implicit request =>
		Buckets.get(name) match {
			case Some(info) => 
				val key = DateKey(year, month, date);
				val csv = info.getCache(key);
				csv match {
					case Some(str) => 
						Ok(str);
					case None =>
						NotFound;
				}
			case _ => NotFound;
		}
	}
	
	
	def status(name: String) = filterAction { implicit request =>
		Buckets.get(name) match {
			case Some(info) => 
				val key = dateForm.bindFromRequest.get
				val csv = info.getCache(key);
				csv match {
					case Some(str) => 
						if (str == "found") Ok(str);
						else if (str == "error") {
							info.removeCache(key);
							Ok(str);
						} else Ok("ready");
					case None => checkS3(info, key);
				}
			case _ => NotFound;
		}
	}
	
	private def checkS3(info: ArchiveInfo, key: DateKey) = {
		val client = new AmazonS3Client(new BasicAWSCredentials(AccessKey, SecretKey));
		val list = client.listObjects(info.bucket, info.directory + key.toDirectory);
		if (list.getObjectSummaries() == null || list.getObjectSummaries().size() == 0) {
			Ok("notFound");
		} else {
			info.putCache(key, "found");
			Akka.future {
				processCSV(client, info, list, key);
			}
			Ok("found");
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
					info.putCache(key, "error");
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
		val csv = analyzer.toString;
		val data = csv.getBytes("utf-8");
		val meta = new ObjectMetadata();
		meta.setContentLength(data.length);
		/*
		client.putObject(Bucket, Base + key + "/summary.csv", 
			new ByteArrayInputStream(data),
			meta);
		*/
		info.putCache(key, csv);
	}
	
	case class ArchiveInfo(name: String, bucket: String, directory: String) {
		def getCache(key: DateKey) = {
			Cache.getAs[String](name + "-" + key.toDateStr);
		}
		
		def putCache(key: DateKey, data: String) = {
			Cache.set(name + "-" + key.toDateStr, data);
		}
		def removeCache(key: DateKey) = {
			Cache.remove(name + "-" + key.toDateStr);
		}
	}
	
	object jqGrid {
		
		def empty = {
			Map(
				"start" -> toJson(1),
				"total" -> toJson(0),
				"page" -> toJson(1),
				"records" -> toJson(0),
				"rows" -> toJson(new Array[JsValue](0))
			);
		}
		
		def data(csv: String) = {
			var idx = 0;
			val rows = csv.split("\n");
			val data = rows.map{ row =>
				val cols = row.split(",");
				idx += 1;
				Map(
					"id" -> toJson("log-" + idx),
					"cell" -> toJson(cols)
				);
			};
			Map(
				"start" -> toJson(1),
				"total" -> toJson(1),
				"page" -> toJson(1),
				"records" -> toJson(rows.size),
				"rows" -> toJson(data)
			);
		}
		
	}
	
}