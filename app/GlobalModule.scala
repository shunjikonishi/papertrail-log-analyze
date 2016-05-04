import javax.inject.Singleton
import java.io.File
import jp.co.flect.util.ResourceGen
import com.google.inject.AbstractModule

@Singleton
class OnStartModule {
  onStart()

  def onStart(): Unit = {
		val defaults = new File("conf/messages")
		val origin = new File("conf/messages.origin")
		if (origin.lastModified > defaults.lastModified) {
			val gen = new ResourceGen(defaults.getParentFile(), "messages")
			gen.process(origin)
		}
		new File("filecache").mkdir()
  }
}

class GlobalModule extends AbstractModule {
  override def configure(): Unit = bind(classOf[OnStartModule]).asEagerSingleton()
}