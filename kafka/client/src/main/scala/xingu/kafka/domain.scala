package xingu.kafka

import java.time.{Clock, LocalDateTime}
import java.time.format.DateTimeFormatter
import org.apache.commons.lang3.RandomStringUtils
import play.api.libs.json.Json

import scala.util.Try

case class Message(topic: String, payload: String, key: Option[String] = None)

object json {
  implicit val MessageReader = Json.reads[Message]
}

object Id {

  val format = DateTimeFormatter.ofPattern("yyyyMMddHHmmss")

  def gen(clock: Clock) = {
    val now = LocalDateTime.now(clock).format(format)
    Id(now + RandomStringUtils.randomAlphanumeric(50)) /* Keep it at 64 chars */
  }
}

case class Id(id: String) {
  val len  = 14
  val path = DateTimeFormatter.ofPattern("yyyy/MM/dd")

  def dir: String = date map { path.format } getOrElse { "old-id-without-date" }
  def date: Try[LocalDateTime] = Try { LocalDateTime.from(Id.format.parse(id.substring(0, len))) }
}