package xingu.commons.play.filter

import akka.stream.Materializer
import akka.util.ByteString
import javax.inject.Inject
import play.api.Configuration
import play.api.http.HttpEntity
import play.api.http.Status.FORBIDDEN
import play.api.mvc.{Filter, RequestHeader, ResponseHeader, Result}
import xingu.commons.utils._

import scala.concurrent.{ExecutionContext, Future}

class AuthFilter @Inject() (
  conf             : Configuration,
  authenticator    : Authenticator,
  implicit val mat : Materializer,
  implicit val ec  : ExecutionContext
) extends Filter {

  def Forbidden(msg: String) = Result(
    header = ResponseHeader(FORBIDDEN),
    body   = HttpEntity.Strict(ByteString(msg), None)
  ).successful()

  override def apply(next: RequestHeader => Future[Result])(request: RequestHeader): Future[Result] = {

    def continueWith(credentials: Option[String]) = {
      credentials match {
        case None       => Forbidden("Missing credentials")
        case Some(cred) => next(authenticator.applyCredentials(request, cred))
      }
    }

    def enforce(request: RequestHeader): Future[Result] =
      authenticator.tokenFrom(request) match {
        case None        => Forbidden("No Token")
        case Some(token) => authenticator.toCredentials(token) flatMap { continueWith }
      }

    if (authenticator.isEnabled)
      if (authenticator.bypass(request))
        next(request)
      else
        enforce(request)
    else
      continueWith(authenticator.defaultCredentials())
  }
}