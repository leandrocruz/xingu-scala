package xingu.commons.play.controllers

import org.slf4j.Logger
import play.api.http.HttpEntity.Strict
import play.api.mvc._
import play.api.mvc.CookieHeaderEncoding
import play.api.mvc.Results._
import play.api.libs.json._
import play.api.libs.ws._
import play.api.http.HeaderNames

import scala.util.control.NonFatal


case class JsonError()

object utils {

  implicit class RequestHelper[R](r: Request[R]) {
    def param(key: String) =
      r.queryString.get(key).flatMap(_.headOption)
  }

  implicit class ResponseHelper(r: WSResponse) {
    def removeContentTypeContentLengthAndSetCookie(key: String): Boolean =
      key != HeaderNames.CONTENT_TYPE && key != HeaderNames.CONTENT_LENGTH &&key != HeaderNames.SET_COOKIE

    def toResult: Result = {
      val cookies    = r.headerValues(HeaderNames.SET_COOKIE)
      val headers    = r.headers.filter({case (key, _) => removeContentTypeContentLengthAndSetCookie(key)}).map({ case (key, values) => (key, values.head)})
      val compressed = if(cookies.isEmpty) None else Some(cookies.mkString(";;")) // same as CookieHeaderEncoding.SetCookieHeaderSeparator
      val parsed     = Cookies.decodeSetCookieHeader(cookies.mkString(";;")).toSeq

//      println(headers)
//      println(compressed)
//      parsed.foreach(println)

      Result(
        header = ResponseHeader(r.status, headers),
        body = Strict(r.bodyAsBytes, Some(r.contentType))
      ).withCookies(parsed: _*)
    }
  }

  implicit class JsonErrorHelper(err: JsError) {
    def toBadRequest = BadRequest(asJsValue(err))

    def asJsValue(err: JsError) = {

      val expected = "expected\\.(\\w+)".r

      def translateMessage(m: String) = {
        m match {
          case expected("jsstring") => "expected.string"
          case expected("jsnumber") => "expected.number"
          case expected("jsobject") => "expected.object"
          case expected("jsarray")  => "expected.array"
          case "path.missing"       => "missing"
          case any => any
        }
      }

      def translateError(error: JsonValidationError) = {
        error
          .messages
          .map(_.substring("error.".length))
          .map(translateMessage)
      }

      def translatePath(path: JsPath) = {
        val translated = path.toJsonString
        if ("obj" == translated)
          "."
        else if (translated.startsWith("obj."))
          translated.substring("obj.".length)
        else if (translated.startsWith("obj"))
          translated.substring("obj".length)
        else
          translated
      }

      val keys = err.errors.map {
        case (path, array) => (translatePath(path), array.flatMap(translateError))
      }

      JsObject(keys.map {
        case (key, errors) =>
          (
            key,
            JsArray(errors.map(i => JsString(i)))
          )
      })
    }
  }

  def ise(log: Logger): PartialFunction[Throwable, Status] = {
    case NonFatal(e) =>
      log.error("Error", e)
      InternalServerError
  }
}
