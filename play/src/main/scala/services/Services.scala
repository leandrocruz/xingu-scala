package services

import java.time.Clock

import akka.actor.ActorSystem
import javax.inject.{Inject, Singleton}
import play.api.{Configuration, Environment}

import scala.concurrent.ExecutionContext

trait Services {
  def ec()         : ExecutionContext
  def env()        : Environment
  def conf()       : Configuration
  def clock()      : Clock
  def actorSystem(): ActorSystem
}

@Singleton
class BasicServices @Inject() (
  executionContext : ExecutionContext,
  environment      : Environment,
  config           : Configuration,
  theClock         : Clock,
  system           : ActorSystem) extends Services {

  override def ec()          : ExecutionContext = executionContext
  override def env()         : Environment      = environment
  override def conf()        : Configuration    = config
  override def clock()       : Clock            = theClock
  override def actorSystem() : ActorSystem      = system
}
