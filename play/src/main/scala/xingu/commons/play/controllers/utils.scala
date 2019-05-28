package xingu.commons.play.controllers

import org.slf4j.Logger
import play.api.http.HttpEntity.Strict
import play.api.mvc._
import play.api.mvc.Results._
import play.api.libs.json._
import play.api.libs.ws._

import scala.util.control.NonFatal


object utils {

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
    def toBadRequest = {
      BadRequest(JsError.toJson(err))
    }
  }

  def ise(log: Logger): PartialFunction[Throwable, Status] = {
    case NonFatal(e) =>
      log.error("Error", e)
      InternalServerError
  }
}
