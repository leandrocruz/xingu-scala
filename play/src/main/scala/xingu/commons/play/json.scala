package xingu.commons.play

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

import play.api.libs.json._

object json {

  val dateTimePattern = "yyyyMMdd'T'HHmmss"

  def localDateTimeReads(pattern: String): Reads[LocalDateTime] = {
    val formatter = DateTimeFormatter.ofPattern(pattern)
    (json: JsValue) => json match {
      case JsString(s) => JsSuccess(LocalDateTime.parse(s, formatter))
      case json => JsError(s"Can't read LocalDateTime from '$json'")
    }
  }

  def localDateTimeWrites(pattern: String): Writes[LocalDateTime] = {
    val formatter = DateTimeFormatter.ofPattern(pattern)
    (it: LocalDateTime) => JsString(formatter.format(it))
  }
}