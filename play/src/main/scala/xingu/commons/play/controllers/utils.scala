package xingu.commons.play.controllers

import org.slf4j.Logger
import play.api.http.HttpEntity.Strict
import play.api.mvc._
import play.api.mvc.Results._
import play.api.libs.json._
import play.api.libs.ws._

import scala.util.control.NonFatal


case class JsonError()

object utils {

  implicit class RequestHelper[R](r: Request[R]) {
    def param(key: String) =
      r.queryString.get(key).flatMap(_.headOption)
  }

  implicit class ResponseHelper(r: WSResponse) {
    def removeContentTypeAndContentLength(header: (String, Seq[String])): Boolean =
      header._1 != "Content-Type" && header._1 != "Content-Length"

    def asTuple(header: (String, Seq[String])) =
      (header._1, header._2.head)

    def toResult: Result = {
      val headers = r.headers filter { removeContentTypeAndContentLength } map { asTuple }
      val h = ResponseHeader(r.status, headers)
      val b = Strict(r.bodyAsBytes, Some(r.contentType))
      Result(h, b)
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
        case (path: JsPath, array: Seq[JsonValidationError]) => {
          (
            translatePath(path),
            array.flatMap(translateError)
          )
        }
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
