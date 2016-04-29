package controllers

import play.api.Logger
import java.util.UUID
import play.api.cache.Cache;
import play.api.Play.current;

import play.api.libs.iteratee.Iteratee
import play.api.libs.iteratee.Enumerator
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import play.api.mvc.WebSocket
import java.util.Date
import jp.co.flect.papertrail.PapertrailClient
import jp.co.flect.papertrail.QueryRequest
import jp.co.flect.papertrail.metrics.LogMetrics
import javax.inject.Inject
import play.api.i18n.MessagesApi
/*
import play.api.mvc.Controller
import play.api.mvc.Action;
import play.api.mvc.AnyContent;
import play.api.mvc.Request;
import play.api.mvc.Result;

import collection.JavaConversions._;

*/

class PapertrailApi @Inject()(val messagesApi: MessagesApi) extends BaseController {
  
  def createSession = filterAction { implicit request =>
    val token = getPostParam("token")
    token.map { s =>
      val key = getPostParam("key").filter(_.nonEmpty).getOrElse(DEFAULT_KEYWORD)
      val hour = getPostParam("hour").getOrElse("6")
      val ptSession = UUID.randomUUID.toString
      Cache.set(ptSession, s)
      Redirect("/pt/show?key=" + key + "&hour=" + hour).withSession(
        "pt-session" -> ptSession
      )
    }.getOrElse(
      BadRequest
    )
  }
  
  def show = filterAction { implicit request =>
    val token = request.session.get("pt-session").flatMap(Cache.getAs[String](_))
    token.map { s =>
      val key = request.getQueryString("key").filter(_.nonEmpty).getOrElse(DEFAULT_KEYWORD)
      val hour = request.getQueryString("hour").getOrElse("6").toDouble
      val url = "ws://" + request.host + "/pt/ws"
      Ok(views.html.realtimeMetrics("PapertrailApi", url, key, hour))
    }.getOrElse(
      BadRequest
    )
  }
  
  def ws = WebSocket.using[String] { implicit request =>
    val token = request.session.get("pt-session").flatMap(Cache.getAs[String](_))
    Logger.info("Connected: PpapertrailApi")
    val key = request.getQueryString("key").filter(_.nonEmpty).getOrElse(DEFAULT_KEYWORD)
    val hour = request.getQueryString("hour").getOrElse("6").toDouble
    
    val in = Iteratee.foreach[String](Logger.info(_)).map { _ =>
      Logger.info("Disconnected: PapertrailApi")
    }
    val out = token.map {
      generateEnumerator(_, key, hour)
    }.getOrElse {
      Enumerator("Can not read logs")
    }
    
    (in, out)
  }
    
  private def generateEnumerator(token: String, key: String, hour: Double)(implicit ec: ExecutionContext): Enumerator[String] = {
    implicit val pec = ec.prepare()
    
    val metrics = new LogMetrics()
    key.split(",").map(_.trim).foreach(metrics.addTarget(_))
    
    val client = new PapertrailClient(token)
    val initialRequest = new QueryRequest(null)
    initialRequest.setMinDate(new Date(System.currentTimeMillis - (hour * 60 * 60 * 1000).toLong))
    var result = client.query(initialRequest)
    var prevId = 0L
    Enumerator.generateM({
      while (prevId == result.getMaxId) {
        val request = new QueryRequest(null)
        request.setMinId(result.getMaxId)
        result = client.query(request)
        if (result.getEventCount() == 0) {
          Thread.sleep(2000)
        }
      } 
      val event = result.getEvents().dropWhile(_.getId <= prevId).find(metrics.`match`(_))
      val ret = event.map { e =>
        if (e.getGeneratedAt == null) {
          e.setGeneratedAt(e.getReceivedAt)
        }
        prevId = e.getId
        e.getProgram + "," + metrics.process(e).toString
      }.getOrElse{
        prevId = result.getMaxId
        "None"
      }
      Future.successful(Option(ret))
    })(pec).onDoneEnumerating{
      Logger.info("Done enumrate")
    } (pec)
  }

}
