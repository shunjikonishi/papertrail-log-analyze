package controllers

import play.api.cache.Cache;
import play.api.Play.current;
import play.api.mvc.Controller
import play.api.mvc.WebSocket
import play.api.mvc.Action;
import play.api.mvc.AnyContent;
import play.api.mvc.Request;
import play.api.mvc.Result;
import play.api.libs.iteratee.Iteratee
import play.api.libs.iteratee.Enumerator
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;

import collection.JavaConversions._;

import java.io.BufferedReader
import java.io.InputStreamReader

import jp.co.flect.heroku.platformapi.PlatformApi;
import jp.co.flect.heroku.platformapi.model.LogSession;

import models.MetricsWebSocket

object RealtimeMetrics extends Controller {

  def login(code: String) = Action { implicit request =>
    val secret = System.getenv().get("HEROKU_OAUTH_SECRET")
    val api = PlatformApi.fromOAuth(secret, code)
    val nonce = api.getSessionNonce()
    Cache.set(nonce, api.getAccessToken())
    Redirect("/rm/appList").withSession(
      "nonce" -> nonce
    )
  }
  
  private def apiAction(name: Option[String])(f: (Request[AnyContent], PlatformApi) => Result): Action[AnyContent] = Action { implicit request =>
    /*
    val token = session.get("nonce").flatMap(Cache.getAs[String](_))
    val username = sys.env.get("HEROKU_USERNAME")
    val apikey = sys.env.get("HEROKU_AUTHTOKEN")
    */
    
    val ret = session.get("nonce").flatMap(
      Cache.getAs[String](_)
    ).map { token =>
      PlatformApi.fromAccessToken(token)
    } map(f(request, _))
    ret.getOrElse (
      Unauthorized("You are not logined to Heroku")
    )
  }
  
  def appList = apiAction(None) { (request, api) =>
    val list = api.getAppList()
    Ok(views.html.herokuAppList(Application.ARCHIVES.keySet, list))
  }
  
  def metrics(name: String) = apiAction(Some(name)) { (request, api) =>
    val key = request.getQueryString("key").getOrElse("memory_rss,memory_total")
    val option = new LogSession()
    option.setLines(1500)
    option.setTail(true)
    val url = api.createLogSession(name, option).getLogplexUrl()
    val host = request.host
    Ok(views.html.realtimeMetrics(host, name, key)).withSession(
      request.session + ("logprex" -> url)
    )
  }
  
  def ws(name: String) = WebSocket.using[String] { implicit request =>
    val key = request.getQueryString("key").getOrElse("memory_rss,memory_total")
    val mws = new MetricsWebSocket(name, session.get("logprex").get, key)
    (mws.in, mws.out)
  }
  
  def test(name: String) = apiAction(Some(name)) { (request, api) =>
    val option = new LogSession()
    option.setLines(1500)
    option.setTail(true)
    val url = api.createLogSession(name, option).getLogplexUrl()
    val host = request.host
    Ok(views.html.websocketTest(host, name)).withSession(
      request.session + ("logprex" -> url)
    )
  }
  
  def testws(name: String) = WebSocket.using[String] { implicit request =>
    val in = Iteratee.foreach[String](println).map { _ =>
      println("Disconnected: " + name)
    }
    val out = session.get("logprex").map { url =>
      generateEnumerator(url)
    }.getOrElse {
      Enumerator("Can not read logs of " + name)
    }
    
    (in, out)
  }
  
  private def generateEnumerator(url: String)(implicit ec: ExecutionContext): Enumerator[String] = {
    implicit val pec = ec.prepare()
    val client = new DefaultHttpClient()
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
      println("Done enumerate");
      reader.close
    } (pec)
  }
}
