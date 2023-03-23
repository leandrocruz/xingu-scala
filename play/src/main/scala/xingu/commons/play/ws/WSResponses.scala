package xingu.commons.play.ws

import akka.stream.scaladsl.Source
import akka.util.ByteString
import play.api.libs.ws.ahc.AhcWSResponse
import play.api.libs.ws.{StandaloneWSResponse, WSCookie, WSResponse}

object responses {

  def Forbidden           (body: Option[String] = None) = response(403, body)
  def NotFound            (body: Option[String] = None) = response(404, body)
  def InternalServerError (body: Option[String] = None) = response(500, body)

  def response(code: Int, bodyText: Option[String]): WSResponse =
    AhcWSResponse {
      new StandaloneWSResponse {
        override def statusText: String                     = ""
        override def underlying[T]: T                       = null.asInstanceOf[T]
        override def body: String                           = bodyText.getOrElse("")
        override def header(key: String): Option[String]    = None
        override def cookie(name: String): Option[WSCookie] = None
        override def bodyAsBytes: ByteString                = ByteString(body)
        override def cookies: Seq[WSCookie]                 = Seq()
        override def status: Int                            = code
        override def headers: Map[String, Seq[String]]      = Map()
        override def bodyAsSource: Source[ByteString, _]    = Source.single(bodyAsBytes)
      }
    }

}
