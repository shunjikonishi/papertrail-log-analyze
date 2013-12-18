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
  
  private def apiAction(f: (Request[AnyContent], PlatformApi) => Result): Action[AnyContent] = Action { implicit request =>
    val ret = session.get("nonce").flatMap(
      Cache.getAs[String](_)
    ).map { token =>
      PlatformApi.fromAccessToken(token)
    } map(f(request, _))
    ret.getOrElse (
      Unauthorized("Oops, you are not logined to Heroku")
    )
  }
  
  def appList = apiAction { (request, api) =>
    val list = api.getAppList()
    Ok(views.html.herokuAppList(Application.ARCHIVES.keySet, list))
  }
  
  def metrics(name: String) = apiAction { (request, api) =>
    val option = new LogSession()
    option.setTail(true)
    val url = api.createLogSession(name, option).getLogplexUrl()
    val host = request.host
println("host: " + host)
    Ok(views.html.realtimeMetrics(host, name)).withSession(
      request.session + ("logprex" -> url)
    )
  }
  
  def ws(name: String) = WebSocket.using[String] { implicit request =>
    val in = Iteratee.foreach[String](println).map { _ =>
      println("Disconnected")
    }
    val out = session.get("logprex").map { url =>
      generateEnumerator(url)
    }.getOrElse {
      Enumerator("Hello! " + name)
    }
    
    (in, out)
  }
  
  private def generateEnumerator(url: String)(implicit ec: ExecutionContext): Enumerator[String] = {
    implicit val pec = ec.prepare()
    val client = new DefaultHttpClient()
    val method = new HttpGet(url)
    val response = client.execute(method)
    val reader = new BufferedReader(new InputStreamReader(response.getEntity().getContent(), "utf-8"))
    Enumerator.generateM({
      val ret = Option(reader.readLine())
      Future.successful(ret)
    })(pec).onDoneEnumerating(reader.close)(pec)
  }
}
