package controllers;

import javax.inject.Inject
import play.api.mvc.Controller;
import play.api.mvc.Action;
import play.api.mvc.Cookie;
import play.api.Play.current;
import play.api.i18n.{I18nSupport, MessagesApi}

class I18N @Inject()(val messagesApi: MessagesApi) extends Controller with I18nSupport {
	
	def setLang(lang: String) = Action { request =>
		Found(request.headers.get("referer").getOrElse("/")).withCookies(Cookie(messagesApi.langCookieName, lang, httpOnly=false))
	}
	
	def messages(lang: String) = Action { request =>
    val map = messagesApi.messages
    val langMap = map("default") ++ map.getOrElse(lang, Map.empty)
		Ok(views.html.messages(langMap.filterKeys(_.startsWith("ui."))))
			.as("text/javascript;charset=\"utf-8\"");
	}
	

}
