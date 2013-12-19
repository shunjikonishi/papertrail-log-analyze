package models

import scala.io.Source
import scala.concurrent.Future

import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.HttpClientBuilder;
import play.api.libs.iteratee.Iteratee
import play.api.libs.iteratee.Enumerator

import jp.co.flect.papertrail.Event
import jp.co.flect.papertrail.metrics.LogMetrics

class MetricsWebSocket(name: String, url: String, key: String) {
  
  private implicit val ec = scala.concurrent.ExecutionContext.Implicits.global
  
  private lazy val source = {
    val client = HttpClientBuilder.create().build()
    val method = new HttpGet(url)
    val response = client.execute(method)
    Source.fromInputStream(response.getEntity().getContent(), "utf-8")
  }
  
  lazy val in = Iteratee.foreach[String](println).map { _ =>
    println("Disconnected: " + name)
    source.close
  }
  
  lazy val out = {
    implicit val pec = ec.prepare()
    val lines = source.getLines()
    var idx = 0
    
    Enumerator.generateM({
      val metrics = new LogMetrics()
      key.split(",").map(_.trim).foreach(metrics.addTarget(_))
      val ret = try {
        lines.map { s =>
          val date = s.substring(0, 19)
          val msg = s.substring(33)
          val pgm = msg.takeWhile(_ != ':')
          val event = new Event(msg)
          event.setGeneratedAt(date)
          event.setProgram(pgm)
          event
        }.find(metrics.`match`(_))
        .map { event =>
          event.getProgram() + "," + metrics.process(event).toString
        }
      } catch {
        case e: Exception =>
          println("Read error: " + name + ", " + e)
          None
      }
      Future.successful(ret)
    })(pec).onDoneEnumerating{
      println("Close: " + name);
      source.close
    } (pec)
  }
}
