package controllers

//import play.api._;
//import play.api.mvc._;
import play.api.mvc.Action;
import play.api.mvc.AnyContent;
import play.api.mvc.Request;
import play.api.mvc.Result;
import play.api.data.Form;
//import play.api.data.Forms._;
import play.api.data.Forms.mapping;
import play.api.data.Forms.text;
import play.api.data.Forms.number;
import play.api.data.Forms.boolean;
import play.api.Play.current;
import play.api.libs.json._;

import java.util.TimeZone;
import java.util.Date;

import jp.co.flect.papertrail.Counter;
import jp.co.flect.papertrail.metrics.LogMetricsCollector;
import jp.co.flect.heroku.platformapi.PlatformApi;
import jp.co.flect.heroku.platformapi.model.Scope;

import models.JqGrid;
import models.JqGrid.GridSort;
import models.LogManager;
import models.LogManager.LogStatus;
import models.LogManager.DateKey;
import models.AnalyzeSetting;

import collection.JavaConversions._;

object Application extends BaseController {
  
  //Initialize
  sys.env.get("TIMEZONE").foreach { str =>
    TimeZone.setDefault(TimeZone.getTimeZone(str));
  };
  
  private val PASSPHRASE = sys.env.get("PASSPHRASE");
  
  private def bucketCheck(name: String)(f: LogManager => Result) = {
    (ARCHIVES.get(name).map{ man =>
      if (man.available) {
        f(man);
      } else {
        InternalServerError("Maybe PAPERTRAIL_ARCHIVE setting is wrong");
      }
    }).getOrElse(NotFound);
  }
  
  private val dateForm = Form(mapping(
    "date" -> text
  )(DateKey.apply)(DateKey.unapply));
  
  private val gridSortForm = Form(mapping(
    "col" -> number(min=0,max=75),
    "asc" -> boolean
  )(GridSort.apply)(GridSort.unapply));
  
  def index = filterAction { implicit request =>
    val herokuUrl = sys.env.get("HEROKU_OAUTH_ID").map(PlatformApi.getOAuthUrl(_, Scope.Read))
    Ok(views.html.index(ARCHIVES.keySet, herokuUrl));
  }
  
  def loganalyzer(name: String) = filterAction { implicit request =>
    val passRequired = PASSPHRASE.isDefined;
    bucketCheck(name) { man =>
      val offset = TimeZone.getDefault().getRawOffset() / (60 * 60 * 1000);
      val setting = man.setting;
      val metricsKeys = if (setting.metricsEnabled) Some(setting.metricsKeys) else None;
      Ok(views.html.loganalyzer(name, passRequired, offset, metricsKeys, ARCHIVES.keySet));
    }
  }
  
  def logcount(name: String) = jqGridData(name, Counter.Type.Count);
  def responsetime(name: String) = jqGridData(name, Counter.Type.Time);
  
  private def jqGridData(name: String, counterType: Counter.Type) = filterAction { implicit request =>
    bucketCheck(name) { man =>
      val key = dateForm.bindFromRequest;
      if (key.hasErrors) {
        Ok(Json.toJson(JqGrid.empty));
      } else {
        val sort = gridSortForm.bindFromRequest;
        man.csv(key.get, counterType).map( csv =>
          Ok(Json.toJson(JqGrid.data(csv, sort.get)))
        ).getOrElse(NotFound);
      }
    }
  }
  
  def show(name: String, date: String) = filterAction { implicit request =>
    bucketCheck(name) { man =>
      val key = DateKey(date);
      man.fullcsv(key).map(Ok(_)).getOrElse(NotFound);
    }
  }
  
  def download(name: String, date: String) = filterAction { implicit request =>
    if (checkPassphrase) {
      bucketCheck(name) { man =>
        val key = DateKey(date);
        val file = man.rawLogFile(key);
        Ok.sendFile(file, fileName={ f=> key.toDateStr + ".tsv.gz"}, onClose={ () => file.delete()});
      }
    } else {
      Forbidden;
    }
  }
  
  def status(name: String) = filterAction { implicit request =>
    bucketCheck(name) { man =>
      val key = dateForm.bindFromRequest;
      if (key.hasErrors) {
        BadRequest;
      } else {
        val status = man.status(key.get);
        status match {
          case LogStatus.Unprocessed =>
            Ok(man.process(key.get).toString);
          case LogStatus.Found | LogStatus.Ready =>
            Ok(status.toString);
          case LogStatus.Error =>
            man.resetStatus(key.get);
            Ok(status.toString);
          case LogStatus.NotFound =>
            throw new IllegalStateException();
        }
      }
    }
  }
  
  def removeSummary(name: String) = filterAction { implicit request =>
    bucketCheck(name) { man =>
      val key = dateForm.bindFromRequest;
      if (key.hasErrors) {
        BadRequest;
      } else {
        Ok(man.removeSummary(key.get).toString);
      }
    }
  }
  
  def setting(name: String) = filterAction { implicit request =>
    if (checkPassphrase) {
      bucketCheck(name) { man =>
        Ok(views.html.setting(man.setting));
      }
    } else {
      Forbidden;
    }
  }
  
  def passphrase(name: String) = filterAction { implicit request =>
    bucketCheck(name) { man =>
      Ok(if (checkPassphrase) "OK" else "NG");
    }
  }
  
  def updateSetting(name: String) = filterAction { implicit request =>
    if (checkPassphrase) {
      bucketCheck(name) { man =>
        val json = getPostParam("json").getOrElse("{}");
        try {
          val newSetting = AnalyzeSetting(json, new Date());
          newSetting.validate match {
            case Some(x) => Ok(x);
            case None =>
              man.updateSetting(newSetting);
              Ok("OK");
          }
        } catch {
          case e: Exception =>
            e.printStackTrace();
            Ok(e.toString());
        }
      }
    } else {
      Forbidden;
    }
  }
  
  private def checkPassphrase(implicit request: Request[AnyContent]) = {
    val pass = getPostParam("passphrase").getOrElse("");
    PASSPHRASE.map(_ == pass).getOrElse(true);
  }
  
  def metrics(name: String, date: String) = filterAction { implicit request =>
    bucketCheck(name) { man =>
      try {
        val file = man.unpackedLogFile(DateKey(date))
        val key = request.getQueryString("key").filter(_.nonEmpty).getOrElse(DEFAULT_KEYWORD)
        val keys = key.split(",").map(_.trim)
        
        val m = new LogMetricsCollector()
        keys.foreach(m.addTarget(_))
        m.process(file);
        val map = m.getResult().mapValues[JsValue](s => JsString(s.toString))  + ("keys" -> JsArray(keys.map(JsString(_))))
        Ok(views.html.metrics(ARCHIVES.keySet, name, key, Json.stringify(JsObject(map.toSeq)), date))
      } catch {
        case e: Exception =>
          NotFound("Log file not found");
      }
    }
  }
}
