import play.api.GlobalSettings;
import play.api.Application;
import play.api.Logger;

import scala.io.Source;
import java.io.File;

object Global extends GlobalSettings {
	
	override def onStart(app: Application) {
		val origin = new File("conf/messages.origin");
		val en = new File("conf/messages");
		val ja = new File("conf/messages.ja");
		if (origin.lastModified > en.lastModified || origin.lastModified > ja.lastModified) {
			val (enIt, jaIt) = Source.fromFile(origin).getLines.partition(_.indexOf("[ja]") == -1);
			println(jaIt.mkString("\n"));
		}
	}
}
