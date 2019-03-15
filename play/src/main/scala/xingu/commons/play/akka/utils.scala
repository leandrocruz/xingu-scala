package xingu.commons.play.akka

import akka.actor.{Actor, ActorContext, ActorRef, ActorSystem, Props}
import xingu.commons.play.akka.utils.Unknown

import scala.concurrent.{Future, Promise}


object utils {

  case object Unknown

  def inquire(target: ActorRef)(msg: Any)(implicit system: ActorSystem) : Future[Any] = {
    val promise = Promise[Any]
    system.actorOf(Props(classOf[Inquire], target, promise)) ! msg
    promise.future
  }
  def unknown()(implicit ctx : ActorContext) = ctx.actorOf(
    Props(classOf[UnknownActor]),
    "unknown"
  )
}

class Inquire(manager: ActorRef, promise: Promise[Any]) extends Actor {

  import context._

  def waitingForReply: Receive = {
    case any =>
      promise.success(any)
      context stop self
  }

  override def receive: Receive = {
    case any =>
      become(waitingForReply)
      manager ! any
  }
}

class UnknownActor extends Actor {
  override def receive = {
    case _ => sender ! Unknown
  }
}
