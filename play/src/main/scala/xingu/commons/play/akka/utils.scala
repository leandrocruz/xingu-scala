package xingu.commons.play.akka

import akka.actor.Actor.Receive
import akka.actor.{Actor, ActorContext, ActorRef, ActorSystem, Props}
import xingu.commons.play.akka.utils.Unknown

import scala.concurrent.{Future, Promise}
import scala.util.Try


object utils {

  case object Unknown

  private def AnyReceiver(promise: Promise[Any]): Receive = {
    case any => promise.success(any)
  }

  private def EitherReceiver[T](promise: Promise[Either[Throwable, T]]): Receive = {
    case i: Either[Throwable, T] => promise.success(i)
    case o => promise.failure(new Exception(s"Received value is not a 'Either[Throwable, T]': $o"))
  }

  private def OptionReceiver[T](promise: Promise[Option[T]]): Receive = {
    case i: Option[T] => promise.success(i)
    case o => promise.failure(new Exception(s"Received value is not a 'Option[T]': $o"))
  }

  private def TryReceiver[T](promise: Promise[Try[T]]): Receive = {
    case i: Try[T] => promise.success(i)
    case o => promise.failure(new Exception(s"Received value is not a 'Try[T]': $o"))
  }

  def inquire(target: ActorRef)(msg: Any)(implicit system: ActorSystem) : Future[Any] = {
    val promise = Promise[Any]
    system.actorOf(Props(classOf[Inquire], target, AnyReceiver(promise))) ! msg
    promise.future
  }

  def inquireEither[T](target: ActorRef)(msg: Any)(implicit system: ActorSystem) : Future[Either[Throwable, T]] = {
    val promise = Promise[Either[Throwable, T]]
    system.actorOf(Props(classOf[Inquire], target, EitherReceiver(promise))) ! msg
    promise.future
  }

  def inquireOption[T](target: ActorRef)(msg: Any)(implicit system: ActorSystem) : Future[Option[T]] = {
    val promise = Promise[Option[T]]
    system.actorOf(Props(classOf[Inquire], target, OptionReceiver(promise))) ! msg
    promise.future
  }

  def inquireTry[T](target: ActorRef)(msg: Any)(implicit system: ActorSystem) : Future[Try[T]] = {
    val promise = Promise[Try[T]]
    system.actorOf(Props(classOf[Inquire], target, TryReceiver(promise))) ! msg
    promise.future
  }

  def unknown()(implicit ctx : ActorContext) = ctx.actorOf(
    Props(classOf[UnknownActor]),
    "unknown"
  )
}

class Inquire(manager: ActorRef, receiver: Receive) extends Actor {

  import context._

  def waitingForReply: Receive = {
    case any =>
      if(receiver.isDefinedAt(any)) receiver.apply(any)
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