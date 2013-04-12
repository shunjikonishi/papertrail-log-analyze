import play.api.GlobalSettings;
import play.api.Application;
import play.api.Logger;

import scala.io.Source;
import java.io.File;

import jp.co.flect.util.ResourceGen;

object Global extends GlobalSettings {
	
	override def onStart(app: Application) {
		//Generate messages and messages.ja
		var msg = new File("conf/messages");
		val origin = new File("conf/messages.origin");
		if (origin.lastModified > msg.lastModified) {
			val gen = new ResourceGen(msg.getParentFile(), "messages");
			gen.process(msg);
		}
	}
}
