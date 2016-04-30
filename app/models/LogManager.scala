package models

import java.io.File
import java.io.ByteArrayInputStream
import java.util.Date
import play.api.Logger
import play.api.cache.Cache
import play.api.Play.current
import play.api.i18n.Lang
import play.api.i18n.Messages
import jp.co.flect.papertrail.LogAnalyzer
import jp.co.flect.papertrail.Counter
import jp.co.flect.papertrail.S3Archive

import play.api.libs.concurrent.Akka
//import collection.JavaConversions._
import collection.JavaConversions.asScalaBuffer
import scala.io.Source

import com.amazonaws.AmazonClientException
import com.amazonaws.AmazonServiceException
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.AmazonS3Exception
import com.amazonaws.services.s3.model.S3Object
import com.amazonaws.services.s3.model.ObjectListing
import com.amazonaws.services.s3.model.ObjectMetadata

import scala.concurrent.duration._

object LogManager {
  
  val ACCESS_KEY = sys.env("S3_ACCESSKEY")
  val SECRET_KEY = sys.env("S3_SECRETKEY")
  
  case class DateKey(date: String) {
    
    def toDateStr = date
    def toDirectory = "/dt=" + date
  }
  
  object LogStatus {
    case object Unprocessed extends LogStatus
    case object Ready extends LogStatus
    case object Found extends LogStatus
    case object NotFound extends LogStatus
    case object Error extends LogStatus
  }
  
  sealed abstract class LogStatus
  
  def apply(name: String, bucket: String, directory: String) = new LogManager(name, bucket, directory)
  
}

class LogManager(val name: String, bucket: String, directory: String) {
  
  import LogManager._
  import CacheManager.Summary
  
  private var _setting: Option[AnalyzeSetting] = None
  
  lazy val available = {
    try {
      setting
      true
    } catch {
      case e: Exception =>
        e.printStackTrace
        false
    }
  }
  
  def setting: AnalyzeSetting = {
    _setting match {
      case Some(v) => v
      case None =>
        val ret = initializeSetting
        _setting = Some(ret)
        ret
    }
  }
  
  def updateSetting(v: AnalyzeSetting) = {
    val client = new AmazonS3Client(new BasicAWSCredentials(ACCESS_KEY, SECRET_KEY))
    val data = v.toString.getBytes("utf-8")
    val meta = new ObjectMetadata()
    meta.setContentLength(data.length)
    client.putObject(bucket, directory + "/analyze.json", new ByteArrayInputStream(data), meta)
    _setting = Some(v)
  }
  
  private def initializeSetting = {
    val client = new AmazonS3Client(new BasicAWSCredentials(ACCESS_KEY, SECRET_KEY))
    try {
      val obj = client.getObject(bucket, directory + "/analyze.json")
      val source = Source.fromInputStream(obj.getObjectContent, "utf-8")
      val lastModified = obj.getObjectMetadata.getLastModified
      AnalyzeSetting(source.mkString, lastModified)
    } catch {
      case e: AmazonS3Exception if e.getStatusCode == 404 =>
        AnalyzeSetting.defaultSetting
      case e: Exception =>
        throw e
    }
  }
  
  def status(key: DateKey) = {
    val man = CacheManager(name)
    val summary = man.get(key)
    if (summary.timestamp.getTime < setting.lastModified.getTime) {
      man.remove(key)
      LogStatus.Unprocessed
    } else {
      summary.status
    }
  }
  
  def csv(key: DateKey, counterType: Counter.Type)(implicit lang: Messages) = {
    val summary = CacheManager(name).get(key)
    summary.status match {
      case LogStatus.Ready => Some(localize(summary.csv(counterType)))
      case _ => None
    }
  }
  
  def fullcsv(key: DateKey)(implicit lang: Messages) = {
    val summary = CacheManager(name).get(key)
    summary.status match {
      case LogStatus.Ready => Some(localize(summary.fullcsv))
      case _ => None
    }
  }
  
  def localize(csv: String)(implicit lang: Messages) = {
    val source = Source.fromString(csv)
    source.getLines.map { row =>
      val cols = row.split("\t")
      cols.toList match {
        case Nil => ""
        case name :: others =>
          val newName = if (name.trim.startsWith("counter.")) {
            val blank = name.takeWhile(_ == ' ')
            val keys = name.trim.split(",")
            keys.toList match {
              case x :: Nil => blank + Messages(x)
              case x :: xs  => blank + Messages(x, xs: _*)
              case Nil      => blank + Messages(name.trim)
            }
          } else {
            name
          }
          (newName :: others).mkString("\t")
      }
    }.mkString("\n")
  }
  
  private def summaryFileName(key: DateKey) = directory + key.toDirectory + "/summary.csv"
  
  def resetStatus(key: DateKey) = CacheManager(name).remove(key)
  def removeSummary(key: DateKey) = {
    val client = new AmazonS3Client(new BasicAWSCredentials(ACCESS_KEY, SECRET_KEY))
    try {
      client.deleteObject(bucket, summaryFileName(key))
      true
    } catch {
      case e: Exception =>
        e.printStackTrace()
        false
    }
  }
  
  def process(key: DateKey) = {
    try {
      checkS3(key)
    } catch {
      case e: Exception => 
        e.printStackTrace()
        LogStatus.Error
    }
  }
  
  private def checkS3(key: DateKey) = {
    val client = new AmazonS3Client(new BasicAWSCredentials(ACCESS_KEY, SECRET_KEY))
    val list = client.listObjects(bucket, directory + key.toDirectory)
    if (list.getObjectSummaries() == null || list.getObjectSummaries().size() == 0) {
      LogStatus.NotFound
    } else {
      val summary = new Summary(LogStatus.Found)
      CacheManager(name).put(key, summary)
      
      import scala.concurrent.ExecutionContext.Implicits.global
      scala.concurrent.Future {
//      Akka.future {
        processCSV(client, list, key)
      }
      summary.status
    }
  }
  
  private def processCSV(client: AmazonS3Client, list: ObjectListing, key: DateKey) = {
    try {
      if (list.getObjectSummaries().exists { obj =>
        obj.getKey().endsWith("/summary.csv") && obj.getLastModified.getTime > setting.lastModified.getTime
      }) {
        downloadCSV(client, key)
      } else {
        prepareCSV(client, key)
      }
    } catch {
      //Include OutOfMemory
      case e: Throwable => 
        e.printStackTrace()
        CacheManager(name).put(key, Summary(LogStatus.Error))
    }
  }
  
  private def downloadCSV(client: AmazonS3Client, key: DateKey) = {
    val obj = client.getObject(bucket, summaryFileName(key))
    val source = Source.fromInputStream(obj.getObjectContent, "utf-8")
    try {
      val csv = source.mkString.split("\n\n")
      val man = CacheManager(name)
      csv.toList match {
        case a :: b :: Nil => 
          man.put(key, Summary(LogStatus.Ready, obj.getObjectMetadata.getLastModified, a, b))
        case _ =>
          man.put(key, Summary(LogStatus.Error))
      }
    } finally {
      source.close
    }
  }
  
  private def prepareCSV(client: AmazonS3Client, key: DateKey) = {
    val t = System.currentTimeMillis
    val analyzer = setting.create
    val s3 = new S3Archive(ACCESS_KEY, SECRET_KEY, bucket, directory)
    val file = File.createTempFile("tmp", ".log")
    try {
      s3.saveToFile(key.toDateStr, true, file)
      analyzer.process(file)
    } finally {
      file.delete
    }
    Logger.info("Analize: " + name + "-" + key.toDateStr + "(" + (System.currentTimeMillis - t) + "ms)")
    
    val countCsv = analyzer.toString(Counter.Type.Count, "\t")
    val timeCsv = analyzer.toString(Counter.Type.Time, "\t")
    val summary = Summary(LogStatus.Ready, new Date(), countCsv, timeCsv)
    CacheManager(name).put(key, summary)
    
    val data = summary.fullcsv.getBytes("utf-8")
    val meta = new ObjectMetadata()
    meta.setContentLength(data.length)
    client.putObject(bucket, summaryFileName(key), 
      new ByteArrayInputStream(data),
      meta)
  }
  
  def rawLogFile(key: DateKey) = {
    val s3 = new S3Archive(ACCESS_KEY, SECRET_KEY, bucket, directory)
    val file = File.createTempFile("tmp", ".tsv.gz")
    s3.saveToFile(key.toDateStr, false, file)
    file
  }
  
  def unpackedLogFile(key: DateKey) = {
    val file = new File("filecache", name + "-" + key.toDateStr + ".tsv")
    if (!file.exists) {
      val s3 = new S3Archive(ACCESS_KEY, SECRET_KEY, bucket, directory)
      s3.saveToFile(key.toDateStr, true, file)
      file.deleteOnExit
    }
    file
  }
}

object CacheManager {
  
  import LogManager.LogStatus
  
  private val CACHE_DURATION = 60 * 60 seconds
  
  case class Summary(val status: LogStatus, val timestamp: Date = new Date(), countCsv: String = null, timeCsv: String = null) {
    
    def csv(counterType: Counter.Type) = counterType match {
      case Counter.Type.Count => countCsv
      case Counter.Type.Time => timeCsv
    }
    
    def fullcsv = countCsv + "\n" + timeCsv
  }
  
  def apply(name: String) = new CacheManager(name)
}

class CacheManager(name: String) {
  
  import CacheManager._
  import LogManager.DateKey
  import LogManager.LogStatus

  def get(key: DateKey) = Cache.getOrElse[Summary](name + "-" + key.toDateStr) { Summary(LogStatus.Unprocessed);}
  def put(key: DateKey, data: Summary) = Cache.set(name + "-" + key.toDateStr, data, CACHE_DURATION)
  def remove(key: DateKey) = Cache.remove(name + "-" + key.toDateStr)
}
