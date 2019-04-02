package xingu.commons.play.filter

import javax.inject.{Inject, Singleton}
import play.api.Configuration
import play.api.mvc.RequestHeader
import xingu.commons.play.config._
import xingu.commons.utils._

import scala.concurrent.Future

trait Authenticator {

  def isEnabled: Boolean
  def bypass(request: RequestHeader): Boolean
  def defaultCredentials(): Option[String]
  def tokenFrom(request: RequestHeader): Option[String]
  def toCredentials(token: String): Future[Option[String]]
  def applyCredentials(request: RequestHeader, credentials: String): RequestHeader
}

@Singleton
class SimpleAuthenticator @Inject() (conf: Configuration) extends Authenticator {

  val (
    enabled,
    requireHttps,
    origins,
    cookie,
    header,
    pathsAllowed,
    defaultCredentialsValue
  ) = conf.withConfig("xingu.authenticator") { c => (
    c.get[Boolean]        ("enabled")             , //
    c.get[Boolean]        ("secure")              , // require https
    c.get[Seq[String]]    ("origins")             , // which domains are allowed. Only enforced if the Origin header is sent
    c.get[String]         ("cookie-prefix")       , // cookie name (input)
    c.get[String]         ("header-prefix")       , // header name (input)
    c.get[Seq[String]]    ("paths.allowed")       , // paths that will bypass authentication
    c.get[Option[String]] ("default-credentials")   // when autthentication is disabled
  )}

  override def isEnabled = enabled

  override def bypass(request: RequestHeader) =
    pathsAllowed.contains(request.path)

  def isOriginAllowed(request: RequestHeader): Option[Boolean] =
    request.headers.get { "Origin" } map { origin =>
      val uri = new java.net.URI(origin)
      val originAllowed   = origins.exists(origin => uri.getHost.endsWith(origin))
      val protocolAllowed = if(requireHttps) uri.getScheme == "https" else true
      originAllowed && protocolAllowed
    } orElse {
      Some(true)
    }

  def extractToken(request: RequestHeader): Option[String] =
    request.cookies.find(_.name == cookie + "token").map(_.value).orElse(request.headers.get(header + "Token"))

  override def tokenFrom(request: RequestHeader) =
    for {
      allowed <- isOriginAllowed { request }
      if allowed
      token <- extractToken { request }
    } yield token

  override def applyCredentials(request: RequestHeader, credentials: String) = {
    val headers = request.headers.add(header + "Credentials" -> credentials)
    request.withHeaders(newHeaders = headers)
  }

  override def defaultCredentials() = defaultCredentialsValue

  override def toCredentials(token: String) = defaultCredentials().successful()

}