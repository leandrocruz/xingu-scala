package xingu.commons.play.controllers

import com.typesafe.config.ConfigRenderOptions
import javax.inject.Inject
import play.api.Configuration
import play.api.http.MimeTypes
import play.api.mvc.InjectedController
import xingu.commons.play.services.Services

class InternalController @Inject() (services: Services) extends InjectedController {
  var count = 0
  def ping() = Action { count +=1 ; Ok(s"pong: $count") }
  def conf() = Action { Ok(services.conf().get[Configuration]("app").underlying.root().render(ConfigRenderOptions.concise())).as(MimeTypes.JSON) }
  def stat() = Action { Ok }
}