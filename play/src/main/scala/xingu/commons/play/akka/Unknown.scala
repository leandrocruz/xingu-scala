package xingu.commons.play.akka

import akka.actor.Actor

case object Unknown

class UnknownActor extends Actor {
  override def receive = {
    case _ => sender ! Unknown
  }
}