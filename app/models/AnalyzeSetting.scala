package models

import play.api.libs.json._

import scala.collection.mutable.ListBuffer
import scala.io.Source
import java.io.File
import java.util.Date
import java.util.regex.Pattern
import java.util.regex.PatternSyntaxException
import jp.co.flect.papertrail.LogAnalyzer
import jp.co.flect.papertrail.Counter

object AnalyzeSetting {
	
	import jp.co.flect.papertrail.counter._

	case class JsLookupWrapper(value: JsLookupResult) {
		def convert: JsValue = {
			value match {
				case JsDefined(v) => v
				case _ => JsNull
			}
		}
	}

	implicit def JsLookupToWrapper(v: JsLookupResult) = JsLookupWrapper(v)
	
	private val counterMap: Map[String, JsonWrapper => List[Counter]] = Map(
		//LogCount
		"allLog" -> { option => 
			new AllLogCounter("counter.allLog") :: Nil
		}, "allAccess" -> { option =>
			new AccessCounter("counter.allAccess") :: Nil
		}, "slowRequest" -> { option =>
			val threshold = option.getAsInt("threshold", 1000)
			new SlowRequestCounter("counter.slowRequest" + "," + threshold, threshold) :: Nil
		}, "slowConnect" -> { option =>
			val threshold = option.getAsInt("threshold", 20)
			new SlowConnectCounter("counter.slowConnect" + "," + threshold, threshold) :: Nil
		}, "serverError" -> { option =>
			new ServerErrorCounter("counter.serverError") :: Nil
		}, "clientError" -> { option =>
			new ClientErrorCounter("counter.clientError") :: Nil
		}, "herokuError" -> { option =>
			new HerokuErrorCounter("counter.herokuError") :: Nil
		}, "dynoStateChanged" -> { option =>
			new DynoStateChangedCounter("counter.dynoStateChanged") :: Nil
		}, "program" -> { option =>
			val counter = new ProgramCounter("counter.program")
			counter.addPattern("app/postgres", "app/postgres\\..*")
			counter.addPattern("app/scheduler", "app/scheduler\\..*")
			counter.addPattern("heroku/scheduler", "heroku/scheduler\\..*")
			counter :: Nil
		}, "regexCount" -> { option =>
			(option.getAsStringArray("pattern").foldLeft(List[Counter]()) { (list, str) =>
				str.split("=").toList match {
					case x :: xs :: Nil => new RegexGroupCounter(x, xs) :: list
					case _ => new RegexGroupCounter(str, str) :: list
				}
			}).reverse
		//ResponseTime
		}, "responseTime" -> { option =>
			val ret = new ResponseTimeCounter("counter.responseTime", "counter.allAccess", "counter.other")
			val includeConnectTime = option.getAsBoolean("includeConnectTime", false)
			ret.setIncludeConnectTime(includeConnectTime)
			applyTimedGroupOption(ret, option)
			
			
			ret :: Nil
		}, "connectTime" -> { option =>
			new ConnectTimeCounter("counter.connectTime") :: Nil
		}, "slowSQL" -> { option =>
			val includeCopy = option.getAsBoolean("includeCopy", true)
			val packCopy = option.getAsBoolean("packCopy", false)
			val duration = option.getAsInt("duration", 50)
			val ret = new PostgresDurationCounter("counter.slowSQL" + "," + duration, "counter.allSQL", "counter.other")
			
			ret.setIncludeCopy(includeCopy)
			ret.setPackCopy(packCopy)
			ret.setTargetDuration(duration)
			applyTimedGroupOption(ret, option)
			
			ret :: Nil
		}, "dynoBoot" -> { option =>
			new DynoBootTimeCounter("counter.dynoBoot") :: Nil
		}, "regexNumber" -> { option =>
			(option.getAsStringArray("pattern").foldLeft(List[Counter]()) { (list, str) =>
				str.split("=").toList match {
					case x :: xs :: Nil => new RegexNumberCounter(x, xs) :: list
					case _ => new RegexNumberCounter(str, str) :: list
				}
			}).reverse
		}
	)
	
	private def applyTimedGroupOption(counter: TimedGroupCounter, option: JsonWrapper) = {
		option.getAsStringArray("pattern").foreach{ str =>
			str.split("=").toList match {
				case x :: xs :: Nil => counter.addPattern(x, xs)
				case _ => counter.addPattern(str, str)
			}
		}
		option.getAsStringArray("exclude").foreach(counter.addExclude(_))
		counter.setMaxGroup(option.getAsInt("maxGroup", 0))
	}
	
	def apply(json: String, lastModified: Date) = new AnalyzeSetting(Json.parse(json), lastModified)
	
	lazy val defaultSetting = {
		val file = new File("app/models/defaultSetting.json")
		val source = Source.fromFile(file, "utf-8")
		try {
			apply(source.mkString, new Date(file.lastModified))
		} finally {
			source.close
		}
	}
	
	private class JsonWrapper(value: JsValue) {
		
		def this(value: JsLookupResult) {
			this(value.convert)
		}
		
		def getAsStringArray(name: String) = {
			(value \ name).convert match {
				case JsArray(v) => v.map(_.as[String])
				case _ => Seq[String]()
			}
		}
		
		def getAsInt(name: String, defaultValue: Int) = {
			(value \ name).convert match {
				case JsNumber(v) => v.intValue
				case _ => defaultValue
			}
		}
		
		def getAsBoolean(name: String, defaultValue: Boolean) = {
			(value \ name).convert match {
				case JsBoolean(v) => v
				case _ => defaultValue
			}
		}
		
		def getAsString(name: String, defaultValue: String) = {
			(value \ name).convert match {
				case JsArray(v) => v.map(_.as[String]).mkString("\n")
				case JsString(v) => v
				case JsNumber(v) => v.toString
				case JsBoolean(v) => v.toString
				case _ => defaultValue
			}
		}
	}
}

class AnalyzeSetting(setting: JsValue, val lastModified: Date) {
	
	import AnalyzeSetting._
	
	def create = {
		val ret = new LogAnalyzer()
		(setting \ "counters").convert match {
			case JsArray(v) =>
				val list = v.foldLeft(new ListBuffer[Counter]()) { (list, el) =>
					val name = el.as[String]
					val option = new JsonWrapper(setting \ "options" \ name)
					list ++= counterMap(name)(option)
				}
				list.foreach { counter =>
					ret.add(counter)
				}
			case _ =>
		}
		ret
	}
	
	def checked(name: String) = {
		(setting \ "counters").convert match {
			case JsArray(v) if v.exists(_.as[String] == name) => "checked"
			case _ => ""
		}
	}
	
	def option(name: String, defaultValue: String) = {
		name.split("\\.").toList match {
			case counterName :: propName :: Nil =>
				new JsonWrapper(setting \ "options" \ counterName).getAsString(propName, defaultValue)
			case _ => ""
		}
	}
	
	def optionChecked(name: String) = {
		option(name, "") match {
			case "true" => "checked"
			case _ => ""
		}
	}
	
	def metricsEnabled = new JsonWrapper(setting \ "metrics").getAsBoolean("enabled", false)
	def metricsKeys = new JsonWrapper(setting \ "metrics").getAsString("keys", "memory_rss,memory_total")
	
	def validate = {
		val errors = new ListBuffer[String]()
		def checkPattern(strs: JsLookupResult) = {
			strs.convert match {
				case JsArray(array) =>
					array.foldLeft(errors) { (list, v) =>
						try {
							Pattern.compile(v.as[String])
						} catch {
							case ex: PatternSyntaxException =>
								list += ex.getMessage()
							case ex: Exception =>
								ex.printStackTrace()
								list += ex.toString
						}
						list
					}
				case _ =>
			}
		}
		(setting \ "counters").convert match {
			case JsArray(array) =>
				if (array.size == 0) {
					errors += "Counter not found"
				} else {
					array.foldLeft(errors) { (list, v) =>
						val name = v.as[String]
						if (counterMap.get(name).isEmpty) {
							list += "Unknown counter: " + name
						}
						list
					}
				}
			case _ => errors += "Counter not found"
		}
		checkPattern(setting \ "options" \ "responseTime" \ "pattern")
		checkPattern(setting \ "options" \ "responseTime" \ "exclude")
		checkPattern(setting \ "options" \ "slowSQL" \ "pattern")
		checkPattern(setting \ "options" \ "slowSQL" \ "exclude")
		checkPattern(setting \ "options" \ "regexCount" \ "pattern")
		checkPattern(setting \ "options" \ "regexNumber" \ "pattern")
		
		if (errors.size == 0) None
		else Some(errors.mkString("\n"))
	}
	
	override def toString = setting.toString
}
