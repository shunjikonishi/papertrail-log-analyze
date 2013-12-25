package controllers

import play.api.Logger
import play.api.mvc.Controller
import play.api.mvc.Action
import play.api.mvc.AnyContent
import play.api.mvc.Request
import play.api.mvc.Result

import org.apache.commons.codec.binary.Base64

import models.LogManager

import jp.co.flect.net.IPFilter

trait BaseController extends Controller {
  
  val ARCHIVES = sys.env.filterKeys(_.startsWith("PAPERTRAIL_ARCHIVE_"))
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
  def filterAction(f: Request[AnyContent] => Result): Action[AnyContent] = Action {request =>
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
    
  def getPostParam(name: String)(implicit request: Request[AnyContent]) = {
    request.body.asFormUrlEncoded.flatMap {
      _.get(name).map(_.head)
    }
  }
  
}