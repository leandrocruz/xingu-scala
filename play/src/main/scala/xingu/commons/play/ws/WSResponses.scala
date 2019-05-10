package xingu.commons.play.ws

import akka.stream.scaladsl.Source
import akka.util.ByteString
import play.api.libs.json.{JsNull, JsValue}
import play.api.libs.ws.{WSCookie, WSResponse}

import scala.xml.Elem

object responses {

  def Forbidden           (body: Option[String] = None) = response(403, body)
  def NotFound            (body: Option[String] = None) = response(404, body)
  def InternalServerError (body: Option[String] = None) = response(500, body)

  def response(code: Int, bodyText: Option[String]) =
    new WSResponse {
      override def statusText: String = ""
      override def underlying[T]: T = null.asInstanceOf[T]
      override def xml: Elem = null
      override def body: String = bodyText.getOrElse("")
      override def header(key: String): Option[String] = None
      override def cookie(name: String): Option[WSCookie] = None
      override def bodyAsBytes: ByteString = ByteString(body)
      override def cookies: Seq[WSCookie] = Seq()
      override def status: Int = code
      override def json: JsValue = JsNull
      override def allHeaders: Map[String, Seq[String]] = Map()
      override def headers: Map[String, Seq[String]] = Map()
      override def bodyAsSource: Source[ByteString, _] = Source.single(bodyAsBytes)
    }
}
