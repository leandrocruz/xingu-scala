package xingu.commons.play.filter

import akka.stream.Materializer
import play.api.Configuration
import play.api.mvc.{Filter, RequestHeader, Result}

import java.time.{Clock, ZonedDateTime}
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.inject.Inject
import scala.concurrent.Future

class JourneyFilter @Inject() (clock: Clock, conf: Configuration, implicit val mat: Materializer) extends Filter {

  private val formatter = DateTimeFormatter.ISO_ZONED_DATE_TIME
  private val me        = conf.get[String]("xingu.journey.me")
  private val header    = conf.get[String]("xingu.journey.header")

  def apply(nextFilter: RequestHeader => Future[Result])(request: RequestHeader): Future[Result] = {

    val now = formatter.format(ZonedDateTime.now(clock))
    val uuid = UUID.randomUUID
    val journey = request.headers.get(header) match {
      case None           =>             s"$me($now)|$uuid"
      case Some(previous) => previous + s",$me($now)"
    }

    nextFilter {
      request.withHeaders(newHeaders = request.headers.replace(header -> journey))
    }
  }
}
