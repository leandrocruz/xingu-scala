package xingu.commons.play

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

import play.api.libs.json.Reads.dateReads
import play.api.libs.json.Writes.dateWrites
import play.api.libs.json._

object json {

  val dateTimePattern = "yyyyMMdd'T'HHmmss"

  def localDateReads(pattern: String) = {
    val formatter = DateTimeFormatter.ofPattern(pattern)
    new Reads[LocalDateTime] {
      override def reads(json: JsValue) = json match {
        case JsString(s) => JsSuccess(LocalDateTime.parse(s, formatter))
        case _           => JsError(s"Can't read LocalDateTime from '$json'")
      }
    }
  }

  def localDateWrites(pattern: String) = {
    val formatter = DateTimeFormatter.ofPattern(pattern)
    (it: LocalDateTime) => JsString(formatter.format(it))
  }

  implicit val DefaultDateReader = dateReads(dateTimePattern)
  implicit val DefaultDateWriter = dateWrites(dateTimePattern)
  implicit val DefaultLocalDateTimeWrites = localDateWrites(dateTimePattern)
  implicit val DefaultLocalDateTimeReads  = localDateReads(dateTimePattern)
}