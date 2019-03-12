package controllers

import java.time.Clock
import java.util.Date
import services._

import com.typesafe.config.ConfigRenderOptions
import javax.inject.Inject
import play.api.libs.json.Json._
import play.api.mvc.InjectedController
import play.api.Configuration
import play.api.http.MimeTypes

class InternalController @Inject() (services: Services) extends InjectedController {
  var count = 0
  def ping() = Action { count +=1 ; Ok(s"pong: $count") }
  def conf() = Action { Ok(services.conf().get[Configuration]("app").underlying.root().render(ConfigRenderOptions.concise())).as(MimeTypes.JSON) }
  def stat() = Action { Ok }
}