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
import play.api.data.Forms.text;
import play.api.data.Forms.number;
import play.api.data.Forms.boolean;
import play.api.Play.current;
import play.api.libs.json.Json.toJson;

import java.util.TimeZone;
import java.util.Date;

import org.apache.commons.codec.binary.Base64;

import jp.co.flect.papertrail.Counter;
import jp.co.flect.net.IPFilter;

import models.JqGrid;
import models.JqGrid.GridSort;
import models.LogManager;
import models.LogManager.LogStatus;
import models.LogManager.DateKey;
import models.AnalyzeSetting;

object Application extends Controller {
	
	//Initialize
	sys.env.get("TIMEZONE").foreach { str =>
		TimeZone.setDefault(TimeZone.getTimeZone(str));
	};
	
	private val PASSPHRASE = sys.env.get("PASSPHRASE");
	
	private val ARCHIVES = sys.env.filterKeys(_.startsWith("PAPERTRAIL_ARCHIVE_"))
		.map{ case(key, value) =>
			val newKey = key.substring("PAPERTRAIL_ARCHIVE_".length).toLowerCase;
			val (bucket, dir) = value.span(_ != '/');
			
			val newValue = LogManager(newKey, bucket,
				if (dir.isEmpty) "papertrail/logs" else dir.substring(1)
			);
			(newKey, newValue);
		};
	
	//IP restriction setting, if required
	private val IP_FILTER = sys.env.get("ALLOWED_IP")
		.map(IPFilter.getInstance(_));
	
	//Basic authentication setting, if required
	private val BASIC_AUTH = sys.env.get("BASIC_AUTHENTICATION")
		.filter(_.split(":").length == 2)
		.map { str =>
			val strs = str.split(":");
			(strs(0), strs(1));
		};
	
	//Apply IP restriction and Basic authentication
	//and Logging
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
		Ok(views.html.index(ARCHIVES.keySet));
	}
	
	def loganalyzer(name: String) = filterAction { implicit request =>
		val passRequired = PASSPHRASE.isDefined;
		bucketCheck(name) { man =>
			val offset = TimeZone.getDefault().getRawOffset() / (60 * 60 * 1000);
			Ok(views.html.loganalyzer(name, passRequired, offset, ARCHIVES.keySet));
		}
	}
	
	def logcount(name: String) = jqGridData(name, Counter.Type.Count);
	def responsetime(name: String) = jqGridData(name, Counter.Type.Time);
	
	private def jqGridData(name: String, counterType: Counter.Type) = filterAction { implicit request =>
		bucketCheck(name) { man =>
			val key = dateForm.bindFromRequest;
			if (key.hasErrors) {
				Ok(toJson(JqGrid.empty));
			} else {
				val sort = gridSortForm.bindFromRequest;
				man.csv(key.get, counterType).map( csv =>
					Ok(toJson(JqGrid.data(csv, sort.get)))
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
	
	private def getPostParam(name: String)(implicit request: Request[AnyContent]) = {
		request.body.asFormUrlEncoded.flatMap {
			_.get(name).map(_.head)
		}
	}
	
}
