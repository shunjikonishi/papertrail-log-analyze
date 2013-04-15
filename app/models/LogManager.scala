package models;

import java.io.File;
import play.api.Logger;
import play.api.cache.Cache;
import play.api.Play.current;
import jp.co.flect.papertrail.LogAnalyzer;
import jp.co.flect.papertrail.Counter;
import jp.co.flect.papertrail.S3Archive;

import play.api.libs.concurrent.Akka;
//import collection.JavaConversions._;
import collection.JavaConversions.asScalaBuffer;
import scala.io.Source;
import java.io.ByteArrayInputStream;

import com.amazonaws.AmazonClientException;
import com.amazonaws.AmazonServiceException;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.services.s3.AmazonS3Client;
import com.amazonaws.services.s3.model.AmazonS3Exception;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.ObjectListing;
import com.amazonaws.services.s3.model.ObjectMetadata;

object LogManager {
	
	val ACCESS_KEY = sys.env("S3_ACCESSKEY");
	val SECRET_KEY = sys.env("S3_SECRETKEY");
	
	case class DateKey(date: String) {
		
		def toDateStr = date;
		def toDirectory = "/dt=" + date;
	};
	
	object LogStatus {
		case object Unprocessed extends LogStatus;
		case object Ready extends LogStatus;
		case object Found extends LogStatus;
		case object NotFound extends LogStatus;
		case object Error extends LogStatus;
	};
	
	sealed abstract class LogStatus;
	
	def apply(name: String, bucket: String, directory: String) = new LogManager(name, bucket, directory);
	
}

class LogManager(val name: String, bucket: String, directory: String) {
	
	import LogManager._;
	import CacheManager.Summary;
	
	private lazy val setting = {
		val client = new AmazonS3Client(new BasicAWSCredentials(ACCESS_KEY, SECRET_KEY));
		try {
			val obj = client.getObject(bucket, directory + "/analyze.json");
			val source = Source.fromInputStream(obj.getObjectContent, "utf-8");
			val lastModified = obj.getObjectMetadata.getLastModified;
			AnalyzeSetting(source.mkString, lastModified);
		} catch {
			case e: AmazonS3Exception if e.getStatusCode == 404 => //Do nothing
			case e: Exception =>
				e.printStackTrace;
		}
		AnalyzeSetting.defaultSetting;
	}
	
	def status(key: DateKey) = CacheManager(name).get(key).status;
	def csv(key: DateKey, counterType: Counter.Type) = {
		val summary = CacheManager(name).get(key);
		summary.status match {
			case LogStatus.Ready => Some(summary.csv(counterType));
			case _ => None;
		}
	}
	
	def fullcsv(key: DateKey) = {
		val summary = CacheManager(name).get(key);
		summary.status match {
			case LogStatus.Ready => Some(summary.fullcsv);
			case _ => None;
		}
	}
	
	private def summaryFileName(key: DateKey) = directory + key.toDirectory + "/summary.csv";
	
	def resetStatus(key: DateKey) = CacheManager(name).remove(key);
	def removeSummary(key: DateKey) = {
		val client = new AmazonS3Client(new BasicAWSCredentials(ACCESS_KEY, SECRET_KEY));
		try {
			client.deleteObject(bucket, summaryFileName(key));
			true;
		} catch {
			case e: Exception =>
				e.printStackTrace();
				false;
		}
	}
	
	def process(key: DateKey) = {
		try {
			checkS3(key);
		} catch {
			case e: Exception => 
				e.printStackTrace();
				LogStatus.Error;
		}
	}
	
	private def checkS3(key: DateKey) = {
		val client = new AmazonS3Client(new BasicAWSCredentials(ACCESS_KEY, SECRET_KEY));
		val list = client.listObjects(bucket, directory + key.toDirectory);
		if (list.getObjectSummaries() == null || list.getObjectSummaries().size() == 0) {
			LogStatus.NotFound;
		} else {
			val summary = new Summary(LogStatus.Found, null, null);
			CacheManager(name).put(key, summary);
			Akka.future {
				processCSV(client, list, key);
			}
			summary.status;
		}
	}
	
	private def processCSV(client: AmazonS3Client, list: ObjectListing, key: DateKey) = {
		try {
			if (list.getObjectSummaries().exists { obj =>
				//obj.getKey().endsWith("/summary.csv") && obj.getLastModified.getTime > setting.lastModified.getTime;
				false;
			}) {
				downloadCSV(client, key);
			} else {
				prepareCSV(client, key);
			}
		} catch {
			//Include OutOfMemory
			case e: Throwable => 
				e.printStackTrace();
				CacheManager(name).put(key, Summary(LogStatus.Error));
		}
	}
	
	private def downloadCSV(client: AmazonS3Client, key: DateKey) = {
		val obj = client.getObject(bucket, summaryFileName(key));
		val source = Source.fromInputStream(obj.getObjectContent, "utf-8");
		try {
			val csv = source.mkString.split("\n\n");
			val man = CacheManager(name)
			csv.toList match {
				case a :: b :: Nil => 
					man.put(key, Summary(LogStatus.Ready, a, b));
				case _ =>
					man.put(key, Summary(LogStatus.Error));
			}
		} finally {
			source.close;
		}
	}
	
	private def prepareCSV(client: AmazonS3Client, key: DateKey) = {
		val t = System.currentTimeMillis;
		/*
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
				"/pc/product/\\d+",
				"/pc/select/author/\\d+",
				"/pc/select/genre/\\d+",
				"/pc/select/genre/\\d+/\\d+",
				"/ebook/detail/[^/]+",
				"/accounts/\\d+",
				"/accounts/\\d+/edit",
				"/contents/\\d+",
				"/account_contents/\\d+",
				"/account_contents/\\d+/edit",
				"/contents/\\d+/license_infos",
				"/api4int/query_password/[^/]+",
				"/api4int/activate/[^/]+",
				"/contents_upload_logs/\\d+",
			//"-rn",
			"-ct",
			"-ss",
			"-dt",
			"-s3", ACCESS_KEY, SECRET_KEY, bucket, directory, key.toDateStr
		);
		val analyzer = LogAnalyzer.process(args);
		*/
		val analyzer = setting.create;
		val s3 = new S3Archive(ACCESS_KEY, SECRET_KEY, bucket, directory);
		val file = File.createTempFile("tmp", ".log");
		try {
			s3.saveToFile(key.toDateStr, true, file);
			analyzer.process(file);
		} finally {
			file.delete;
		}
		Logger.info("Analize: " + name + "-" + key.toDateStr + "(" + (System.currentTimeMillis - t) + "ms)");
		
		val countCsv = analyzer.toString(Counter.Type.Count, "\t");
		val timeCsv = analyzer.toString(Counter.Type.Time, "\t");
		val summary = Summary(LogStatus.Ready, countCsv, timeCsv);
		CacheManager(name).put(key, summary);
		
		val data = summary.fullcsv.getBytes("utf-8");
		val meta = new ObjectMetadata();
		meta.setContentLength(data.length);
		client.putObject(bucket, summaryFileName(key), 
			new ByteArrayInputStream(data),
			meta);
	}
}

object CacheManager {
	
	import LogManager.LogStatus;
	
	private val CACHE_DURATION = 60 * 60;
	
	case class Summary(val status: LogStatus, countCsv: String = null, timeCsv: String = null) {
		
		def csv(counterType: Counter.Type) = counterType match {
			case Counter.Type.Count => countCsv;
			case Counter.Type.Time => timeCsv;
		}
		
		def fullcsv = countCsv + "\n" + timeCsv;
	}
	
	def apply(name: String) = new CacheManager(name);
}

class CacheManager(name: String) {
	
	import CacheManager._;
	import LogManager.DateKey;
	import LogManager.LogStatus;
	
	def get(key: DateKey) = Cache.getOrElse[Summary](name + "-" + key.toDateStr) { Summary(LogStatus.Unprocessed);}
	def put(key: DateKey, data: Summary) = Cache.set(name + "-" + key.toDateStr, data, CACHE_DURATION);
	def remove(key: DateKey) = Cache.remove(name + "-" + key.toDateStr);
}

