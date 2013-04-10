package models;

import play.api.cache.Cache;
import play.api.Play.current;
import jp.co.flect.papertrail.Counter;

object CacheManager {
	
	object CacheStatus {
		case object Unprocessed extends CacheStatus;
		case object Ready extends CacheStatus;
		case object Found extends CacheStatus;
		case object NotFound extends CacheStatus;
		case object Error extends CacheStatus;
	};
	
	sealed abstract class CacheStatus;
	
	case class DateKey(year: Int, month: Int, date: Int) {
		def toDateStr = {
			"%d-%02d-%02d".format(year, month, date);
		}
		def toDirectory = {
			"/dt=%d-%02d-%02d".format(year, month, date);
		}
	};
	
	case class Summary(val status: CacheStatus, countCsv: String = null, timeCsv: String = null) {
		
		def csv(counterType: Counter.Type) = counterType match {
			case Counter.Type.Count => countCsv;
			case Counter.Type.Time => timeCsv;
		}
		
		def fullcsv = countCsv + timeCsv;
	}
	
	def apply(name: String) = new CacheManager(name);
}

class CacheManager(name: String) {
	
	import CacheManager._;
	
	def get(key: DateKey) = Cache.getOrElse[Summary](name + "-" + key.toDateStr) { Summary(CacheStatus.Unprocessed);}
	def put(key: DateKey, data: Summary) = Cache.set(name + "-" + key.toDateStr, data);
	def remove(key: DateKey) = Cache.remove(name + "-" + key.toDateStr);
}
