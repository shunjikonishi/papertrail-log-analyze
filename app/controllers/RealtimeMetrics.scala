package controllers

import play.api.Logger
import play.api.cache.CacheApi
import play.api.Play.current
import play.api.mvc.Controller
import play.api.mvc.WebSocket
import play.api.mvc.Action
import play.api.mvc.AnyContent
import play.api.mvc.Request
import play.api.mvc.Result
import play.api.libs.iteratee.Iteratee
import play.api.libs.iteratee.Enumerator
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import org.apache.http.client.HttpClient
import org.apache.http.client.methods.HttpGet
import org.apache.http.impl.client.HttpClientBuilder

import collection.JavaConversions._

import java.io.BufferedReader
import java.io.InputStreamReader

import jp.co.flect.heroku.platformapi.PlatformApi
import jp.co.flect.heroku.platformapi.model.LogSession

import models.MetricsWebSocket
import javax.inject.Inject
import play.api.i18n.MessagesApi

class RealtimeMetrics @Inject()(val messagesApi: MessagesApi, cache: CacheApi) extends BaseController {

  def login(code: String) = filterAction { implicit request =>
    val secret = System.getenv().get("HEROKU_OAUTH_SECRET")
    val api = PlatformApi.fromOAuth(secret, code)
    val nonce = api.getSessionNonce()
    cache.set(nonce, api.getAccessToken())
    Redirect("/rm/appList").withSession(
      "nonce" -> nonce
    )
  }
  
  private def apiAction(name: Option[String])(f: (Request[AnyContent], PlatformApi) => Result): Action[AnyContent] = filterAction { implicit request =>
    val token = request.session.get("nonce").flatMap(cache.get[String](_))
    val api = token.map(PlatformApi.fromAccessToken(_))
    api.map(f(request, _)).getOrElse (Unauthorized("Not logged in"))
  }
  
  def appList = apiAction(None) { (request, api) =>
    val list = api.getAppList()
    Ok(views.html.herokuAppList(list))
  }
  
  def metrics(name: String) = apiAction(Some(name)) { (request, api) =>
    val key = request.getQueryString("key").filter(_.nonEmpty).getOrElse(DEFAULT_KEYWORD)
    val option = new LogSession()
    option.setLines(1500)
    option.setTail(true)
    val url = api.createLogSession(name, option).getLogplexUrl()
    val wsUrl = "ws://" + request.host + "/rm/ws/" + name
    Ok(views.html.realtimeMetrics(name, wsUrl, key, 0)).withSession(
      request.session + ("logprex" -> url)
    )
  }
  
  def ws(name: String) = WebSocket.using[String] { implicit request =>
    Logger.info("Connected: " + name)
    val key = request.getQueryString("key").filter(_.nonEmpty).getOrElse(DEFAULT_KEYWORD)
    val mws = new MetricsWebSocket(name, request.session.get("logprex").get, key)
    (mws.in, mws.out)
  }
  
  def test(name: String) = apiAction(Some(name)) { (request, api) =>
    implicit val language = lang(request)
    val option = new LogSession()
    option.setLines(1500)
    option.setTail(true)
    val url = api.createLogSession(name, option).getLogplexUrl()
    val wsUrl = "ws://" + request.host + "/rm/testws/" + name
    Ok(views.html.websocketTest(name, url)).withSession(
      request.session + ("logprex" -> url)
    )
  }
  
  def testws(name: String) = WebSocket.using[String] { implicit request =>
    Logger.info("Connected: " + name)
    val in = Iteratee.foreach[String](Logger.info(_)).map { _ =>
      Logger.info("Disconnected: " + name)
    }
    val out = request.session.get("logprex").map { url =>
      generateEnumerator(url)
    }.getOrElse {
      Enumerator("Can not read logs of " + name)
    }
    
    (in, out)
  }
  
  private def generateEnumerator(url: String)(implicit ec: ExecutionContext): Enumerator[String] = {
    implicit val pec = ec.prepare()
    val client = HttpClientBuilder.create().build()
    val method = new HttpGet(url)
    val response = client.execute(method)
    val reader = new BufferedReader(new InputStreamReader(response.getEntity().getContent(), "utf-8"))
    var idx = 0
    Enumerator.generateM({
      idx += 1
      val ret = try {
        Option(reader.readLine()).map(idx + ": " + _)
      } catch {
        case e: Exception =>
          e.printStackTrace
          None
      }
      Future.successful(ret)
    })(pec).onDoneEnumerating{
      Logger.info("Done enumrate")
      reader.close
    } (pec)
  }
}
