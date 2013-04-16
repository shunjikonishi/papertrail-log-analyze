package models;

import play.api.libs.json.Json;
import play.api.libs.json.JsValue;
import play.api.libs.json.JsNumber;
import play.api.libs.json.JsBoolean;
import play.api.libs.json.JsArray;
import play.api.libs.json.JsString;

import scala.collection.mutable.ListBuffer;
import scala.io.Source;
import java.io.File;
import java.util.Date;
import jp.co.flect.papertrail.LogAnalyzer;
import jp.co.flect.papertrail.Counter;

object AnalyzeSetting {
	
	import jp.co.flect.papertrail.counter._;
	
	private val counterMap: Map[String, JsonWrapper => List[Counter]] = Map(
		//LogCount
		"allLog" -> { option => 
			new AllLogCounter("counter.allLog") :: Nil;
		}, "allAccess" -> { option =>
			new AccessCounter("counter.allAccess") :: Nil;
		}, "slowRequest" -> { option =>
			val threshold = option.getAsInt("threshold", 1000);
			new SlowRequestCounter("counter.slowRequest" + "," + threshold, threshold) :: Nil;
		}, "slowConnect" -> { option =>
			val threshold = option.getAsInt("threshold", 20);
			new SlowConnectCounter("counter.slowConnect" + "," + threshold, threshold) :: Nil;
		}, "serverError" -> { option =>
			new ServerErrorCounter("counter.serverError") :: Nil;
		}, "clientError" -> { option =>
			new ClientErrorCounter("counter.clientError") :: Nil;
		}, "herokuError" -> { option =>
			new HerokuErrorCounter("counter.herokuError") :: Nil;
		}, "dynoStateChanged" -> { option =>
			new DynoStateChangedCounter("counter.dynoStateChanged") :: Nil;
		}, "program" -> { option =>
			new ProgramCounter("counter.program") :: Nil;
		/*
		}, "regexCount" -> { option =>
			option.map
			new ProgramCounter("counter.program") :: Nil;
		*/
		//ResponseTime
		}, "responseTime" -> { option =>
			val ret = new ResponseTimeCounter("counter.responseTime", "counter.allAccess", "counter.other");
			val includeConnectTime = option.getAsBoolean("includeConnectTime", false);
			ret.setIncludeConnectTime(includeConnectTime);
			applyTimedGroupOption(ret, option);
			
			
			ret :: Nil;
		}, "connectTime" -> { option =>
			new ConnectTimeCounter("counter.connectTime") :: Nil;
		}, "slowSQL" -> { option =>
			val includeCopy = option.getAsBoolean("includeCopy", true);
			val packCopy = option.getAsBoolean("packCopy", false);
			val duration = option.getAsInt("duration", 50);
			val ret = new PostgresDurationCounter("counter.slowSQL" + "," + duration, "counter.allSQL", "counter.other");
			
			ret.setIncludeCopy(includeCopy);
			ret.setPackCopy(packCopy);
			ret.setTargetDuration(duration);
			applyTimedGroupOption(ret, option);
			
			ret :: Nil;
		}, "dynoBoot" -> { option =>
			new DynoBootTimeCounter("counter.dynoBoot") :: Nil;
		/*
		}, "regexNumber" -> { option =>
			option.map
			new ProgramCounter("counter.program") :: Nil;
		*/
		}
	);
	
	private def applyTimedGroupOption(counter: TimedGroupCounter, option: JsonWrapper) = {
		option.getAsStringArray("pattern").foreach{ str =>
			str.split(",").toList match {
				case x :: xs => counter.addPattern(x, xs.mkString(","));
				case _ => counter.addPattern(str, str);
			}
		};
		option.getAsStringArray("exclude").foreach(counter.addExclude(_));
		counter.setMaxGroup(option.getAsInt("maxGroup", 0));
	}
	
	def apply(json: String, lastModified: Date) = new AnalyzeSetting(Json.parse(json), lastModified);
	
	lazy val defaultSetting = {
		val file = new File("app/models/defaultSetting.json");
		val source = Source.fromFile(file, "utf-8");
		try {
			apply(source.mkString, new Date(file.lastModified));
		} finally {
			source.close;
		}
	}
	
	private class JsonWrapper(value: JsValue) {
		
		def getAsStringArray(name: String) = {
			(value \ name) match {
				case JsArray(v) => v.map(_.as[String]);
				case _ => Seq[String]();
			}
		}
		
		def getAsInt(name: String, defaultValue: Int) = {
			(value \ name) match {
				case JsNumber(v) => v.intValue;
				case _ => defaultValue;
			}
		}
		
		def getAsBoolean(name: String, defaultValue: Boolean) = {
			(value \ name) match {
				case JsBoolean(v) => v;
				case _ => defaultValue;
			}
		}
		
		def getAsString(name: String, defaultValue: String) = {
			(value \ name) match {
				case JsArray(v) => v.map(_.as[String]).mkString("\n");
				case JsString(v) => v;
				case JsNumber(v) => v.toString;
				case JsBoolean(v) => v.toString;
				case _ => defaultValue;
			}
		}
	}
}

class AnalyzeSetting(setting: JsValue, val lastModified: Date) {
	
	import AnalyzeSetting._;
	
	def create = {
		val ret = new LogAnalyzer();
		(setting \ "counters") match {
			case JsArray(v) =>
				val list = v.foldLeft(new ListBuffer[Counter]()) { (list, el) =>
					val name = el.as[String];
					val option = new JsonWrapper(setting \ "options" \ name);
					list ++= counterMap(name)(option);
				}
				list.foreach { counter =>
					ret.add(counter);
				}
			case _ =>
		}
		ret;
	}
	
	def checked(name: String) = {
		(setting \ "counters") match {
			case JsArray(v) if v.exists(_.as[String] == name) => "checked";
			case _ => "";
		}
	}
	
	def option(name: String, defaultValue: String) = {
		name.split("\\.").toList match {
			case counterName :: propName :: Nil =>
				new JsonWrapper(setting \ "options" \ counterName).getAsString(propName, defaultValue);
			case _ => "";
		}
	}
	
	def optionChecked(name: String) = {
		option(name, "") match {
			case "true" => "checked";
			case _ => "";
		}
	}
	
}
