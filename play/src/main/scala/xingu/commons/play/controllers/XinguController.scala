package xingu.commons.play.controllers

import akka.actor.{ActorRef, ActorSystem}
import play.api.libs.json._
import play.api.mvc.Results._
import play.api.mvc.{Request, Result}
import xingu.commons.play.akka.utils._

import scala.concurrent.{ExecutionContext, Future}
import xingu.commons.utils._
import xingu.commons.play.controllers.utils._

import scala.util.Failure
import scala.util.control.NonFatal

trait XinguController {

  def toResult[T](f: Future[Option[T]])(implicit writer: Writes[T], ec: ExecutionContext): Future[Result] =
    f map {
      _.map { it => Ok(Json.toJson(it)) } getOrElse { NotFound }
    } recover {
      case NonFatal(e) => InternalServerError(e.getMessage)
    }

  def validateThen[R](fn: (Request[JsValue], R)  => Future[Result])(implicit reader: Reads[R]): Request[JsValue] => Future[Result] = {
    r: Request[JsValue] =>
      r.body.validate[R] match {
        case err : JsError      => err.toBadRequest.successful()
        case ok  : JsSuccess[R] => fn(r, ok.get)
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
