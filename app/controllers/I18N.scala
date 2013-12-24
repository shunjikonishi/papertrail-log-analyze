package controllers;

import play.api.mvc.Controller;
import play.api.mvc.Action;
import play.api.cache.Cached;
import play.api.Play.current;
import play.api.i18n.Lang;

object I18N extends Controller {
	
	def setLang(lang: String) = Action { request =>
		Found(request.headers.get("referer").getOrElse("/")).withLang(Lang(lang));
	}
	
	def messages(lang: String) = Action { request =>
		import play.api.Play;
		import play.api.i18n.MessagesPlugin;
		
		val map = Play.current.plugin[MessagesPlugin]
			.map(_.api.messages).getOrElse(Map.empty);
		Ok(views.html.messages(map.getOrElse(lang, map("default")).filterKeys(_.startsWith("ui."))))
			.as("text/javascript;charset=\"utf-8\"");
	}
	
}
