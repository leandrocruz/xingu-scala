package xingu.commons.play.controllers

import com.typesafe.config.ConfigRenderOptions
import play.api.Configuration
import play.api.http.MimeTypes
import play.api.libs.json.Json
import play.api.mvc.InjectedController
import xingu.commons.play.services.Services

import java.time.{ZoneId, ZonedDateTime}
import javax.inject.Inject

class InternalController @Inject() (services: Services) extends InjectedController {
  var count = 0
  def ping() = Action { count +=1 ; Ok(s"pong: $count") }
  def conf() = Action { Ok(services.conf().get[Configuration]("app").underlying.root().render(ConfigRenderOptions.concise())).as(MimeTypes.JSON) }
  def stat() = Action { Ok }

  def echo() = Action { req =>
    val qs = req.queryString.map({case (key, values) => s"'$key' = '${values.mkString(", ")}'"}).mkString(", ")
    val result = s"uri: '${req.uri}'\npath: '${req.path}'\nquery: '$qs'"
    Ok(result).withHeaders(req.headers.headers: _*)
  }

  def now() = Action { r =>
    val clock = services.clock()
    val tz    = r.queryString.get("zone").map(_.head).getOrElse(clock.getZone.getId)
    val now   = ZonedDateTime.now(clock.withZone(ZoneId.of(tz)))
    Ok {
      Json.obj("now"  -> now, "zone" -> tz)
    }
  }

  def buildInfo() = Action {
    services
      .env()
      .resourceAsStream("build-info.json")
      .map(Json.parse)
      .map(Ok(_))
      .getOrElse(NotFound)
  }
}