package xingu.commons.play.akka

import akka.actor.{Actor, ActorContext, ActorRef, Props}

import scala.concurrent.duration.FiniteDuration
import scala.util.Random

case object StopCountingAndFlushAfterTimeout
case object Terminate
case object FilterDoesNotMatch

object Aggregator {
  def create[T](ctx: ActorContext, replyTo: ActorRef, timeout: Option[FiniteDuration], expectedCount: Option[Int]) = {
    val id      = Random.alphanumeric.take(10).mkString
    val props   = Props(classOf[Aggregator[T]], replyTo, timeout, expectedCount).withMailbox("aggregator-mailbox")
    ctx.actorOf(props, s"aggregator-$id")
  }
}

class Aggregator[T](
  val replyTo: ActorRef,
  val timeout: Option[FiniteDuration],
  val expected: Option[Int])
  extends Actor
{
  import context._

  expected foreach { value =>
    if(value <= 0)
      throw new IllegalArgumentException(s"Expected must be greater than ZERO: $value")
  }

  val buffer    = scala.collection.mutable.ListBuffer[T]()
  var flushed   = false
  var noMatch   = 0

  override def preStart() = timeout foreach {
    system.scheduler.scheduleOnce(_, self, StopCountingAndFlushAfterTimeout)
  }

  override def receive: Receive = {

    case StopCountingAndFlushAfterTimeout => flushAndStop(true)
    case FilterDoesNotMatch => appendAnd { noMatch += 1 }
    case response           => appendAnd { buffer  += response.asInstanceOf[T] }
  }

  def flushAndStop(stop: Boolean) = {
    if(!flushed) {
      flushed = true
      replyTo ! buffer.clone().toSeq
      buffer.clear()
    }

    if(stop)
      context.stop(self)
  }

  def appendAnd(fn: => Unit): Unit = if(!flushed) {
    fn
    expected foreach { expectedCount =>
      if(expectedCount  == noMatch + buffer.size) flushAndStop(timeout.isEmpty)
    }
  }
}