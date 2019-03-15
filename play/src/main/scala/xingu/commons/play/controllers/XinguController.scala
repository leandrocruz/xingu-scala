package xingu.commons.play.controllers

import akka.actor.{ActorRef, ActorSystem}
import play.api.libs.json.{JsError, JsSuccess, JsValue, Reads}
import play.api.mvc.Results.{InternalServerError, NotFound}
import play.api.mvc.{Request, Result}
import xingu.commons.play.akka.Inquire.inquire
import xingu.commons.play.akka.Unknown

import scala.concurrent.{ExecutionContext, Future}
import xingu.commons.utils._
import xingu.commons.play.controllers.utils._

import scala.util.Failure

trait XinguController {
  def validateThen[R](fn: R => Future[Result])(implicit request: Request[JsValue], reader: Reads[R]): Future[Result] = {
    request.body.validate[R] match {
      case err : JsError      => err.toBadRequest.successful()
      case ok  : JsSuccess[R] => fn(ok.get)
    }
  }

  def inquireRoot[T](ref: ActorRef)(msg: Any)(handler: T => Result)(implicit system: ActorSystem, ec: ExecutionContext) = {
    inquire(ref) { msg } map {
      case Failure(e) => InternalServerError(e.getMessage)
      case Unknown    => NotFound
      case item: T    => handler(item)
    }
  }
}
