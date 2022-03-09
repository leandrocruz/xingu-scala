package xingu.kafka.consumer

object api {

  import akka.actor.ActorRef
  import play.api.libs.json.JsValue

  case object Metrics
  case class  Event(topic: String, offset: Long, partition: Int, key: String, kind: String, value: JsValue)

  case class StartConsuming(topics: Seq[String])
  case class StopConsuming(topics: Seq[String])

  trait XinguKafkaEventHandler {
    def process(event: Event): Unit
  }

  trait XinguKafkaConsumer {
    def metrics()
    def supervisor(): Option[ActorRef]
  }
}

object impl {

  import akka.actor.{Actor, ActorRef, Props, Timers}
  import akka.pattern.pipe
  import api._
  import org.apache.kafka.clients.consumer.{ConsumerRecord, KafkaConsumer}
  import org.apache.kafka.common.TopicPartition
  import org.apache.kafka.common.serialization.StringDeserializer
  import org.slf4j.LoggerFactory
  import play.api.Configuration
  import play.api.libs.json._
  import xingu.commons.play.services.Services
  import xingu.kafka.storage.api._
  import xingu.kafka.storage.api.json._

  import java.lang
  import java.time.LocalDateTime
  import java.util.Properties
  import javax.inject.{Inject, Singleton}
  import scala.collection.JavaConverters._
  import scala.collection.Seq
  import scala.concurrent.Future
  import scala.concurrent.duration._
  import scala.language.postfixOps
  import scala.util.control.NonFatal
  import scala.util.{Failure, Success, Try}

  case object Refresh
  case object CloseConsumer
  case class  ConsumerClosed(error: Option[Throwable])

  @Singleton
  class SimpleXinguKafkaConsumer @Inject()(services : Services, handler: XinguKafkaEventHandler, storage: XinguKafkaStorage) extends XinguKafkaConsumer {

    private val logger = LoggerFactory.getLogger(getClass)
    private val ref    = build()

    private def build() = {
      val enabled = services.conf().getOptional[Boolean]("xingu.kafka.consumer.enabled").getOrElse(true)
      if(enabled) {
        val props = Props(classOf[KafkaSupervisor], services, handler, storage)
        Some(services.actorSystem().actorOf(props, "xingu-kafka-consumer-supervisor"))
      } else {
        logger.warn("KafkaSupervisor is not enabled")
        None
      }
    }

    override def metrics() = {
      ref match {
        case Some(actor) => actor ! Metrics
        case None        => logger.warn("KafkaSupervisor is not enabled")
      }
    }

    override def supervisor() = ref
  }

  class ConsumerFactory (conf: Configuration) {

    val logger       = LoggerFactory.getLogger(getClass)
    val timeout      = conf.getOptional[Duration]("consumer.timeout").getOrElse(5 minutes)
    val alwaysCommit = conf.get[Boolean]("consumer.alwaysCommit")

    def timeoutInMillis = timeout.toMillis

    private def startFromGiven(topic: String) = {
      new lang.Long(0) /* this is ugly, but I can' t make it work with auto boxing */
    }

    def createConsumer(topics: Seq[String], from: Option[LocalDateTime] = None) = Try {
      val id      = conf.get[String] ("consumer.id")
      val group   = conf.get[String] ("consumer.group")
      val servers = conf.get[String] ("servers")
      val key     = conf.get[String] ("key")
      val secret  = conf.get[String] ("secret")

      logger.info(
        s"""Kafka Consumer Config:
           | group   : $group
           | topics  : ${topics.mkString(", ")}
           | servers : $servers""".stripMargin
      )

      val props = Map(
        "client.id"                             -> id,
        "group.id"                              -> group,
        "bootstrap.servers"                     -> servers,
        "security.protocol"                     -> "SASL_SSL",
        "sasl.jaas.config"                      -> s"""org.apache.kafka.common.security.plain.PlainLoginModule required username="$key" password="$secret";""",
        "ssl.endpoint.identification.algorithm" -> "https",
        "sasl.mechanism"                        -> "PLAIN",
        "auto.offset.reset"                     -> "earliest",
        "key.deserializer"                      -> classOf[StringDeserializer].getName,
        "value.deserializer"                    -> classOf[StringDeserializer].getName
      )

      val config = new Properties()
      props.foreach({
        case (key, value) => config.put(key, value)
      })

      val consumer = new KafkaConsumer[String, String](config)

      if(from.isDefined && false) {
        /**
         * See: https://medium.com/@werneckpaiva/how-to-seek-kafka-consumer-offsets-by-timestamp-de351ba35c61
         */
        topics foreach { topic =>
          val startFrom  = startFromGiven(topic)
          val partitions = consumer.partitionsFor(topic).asScala.map(info => new TopicPartition(topic, info.partition()))
          consumer.assign(partitions.asJava)
          val timestampByPartition = partitions.map(it => (it, startFrom)).toMap
          val offsets = consumer.offsetsForTimes(timestampByPartition.asJava).asScala
          offsets.foreach {
            case (partition, offset) => consumer.seek(partition, offset.offset())
          }
        }
      } else {
        consumer.subscribe(topics.asJava)
      }

      consumer
    }
  }

  class KafkaSupervisor(services: Services, messageHandler: XinguKafkaEventHandler, storage: XinguKafkaStorage) extends Actor with Timers {

    private implicit val ec  = services.ec()

    private val logger       = LoggerFactory.getLogger(getClass)
    private val conf         = services.conf().get[Configuration]("xingu.kafka")
    private val factory      = new ConsumerFactory(conf)
    private val timeout      = java.time.Duration.ofMillis(factory.timeoutInMillis)
    private val refreshEvery = conf.getOptional[FiniteDuration]("consumer.refreshEvery").getOrElse(1 hour)
    private val handler      = services.actorSystem().actorOf(Props(classOf[EventHandler], services, messageHandler, storage ), "xingu-kafka-event-handler")
    private var topics       = services.conf().get[Seq[String]]("xingu.kafka.consumer.startConsuming")
    private var consumerRef  = startNext()
    private var count = 0

    context.system.scheduler.scheduleAtFixedRate(refreshEvery, refreshEvery, self, Refresh)

    private def startNext(): Option[ActorRef] = {
      if(topics.nonEmpty) {
        logger.info(s"Creating new consumer for topics: ${topics.mkString(", ")}")
        factory.createConsumer(topics) match {
          case Success(consumer) =>
            count = count + 1
            Some(services.actorSystem().actorOf(Props(classOf[ConsumerSupervisor], services, self, consumer, timeout, handler), s"kafka-consumer-supervisor-$count"))

          case Failure(e) =>
            logger.error("Error creating kafka consumer", e)
            None
        }
      } else {
        logger.warn("No topics to consume")
        None
      }
    }

    private def start(coll: Seq[String]) = {
      if(coll.nonEmpty) {
        topics = topics ++ coll
        self ! Refresh
//        consumerRef = consumerRef.orElse(startNext())
//        consumerRef foreach { _ ! StartConsuming(topics) }
      }
    }

    private def stop(coll: Seq[String]) = {
      if(coll.nonEmpty) {
        topics = topics.filterNot(coll.contains)
        self ! Refresh
//        val signal = if(topics.isEmpty) CloseConsumer else StopConsuming(topics)
//        consumerRef foreach { _ ! signal }
      }
    }

    override def receive = {
      case StartConsuming(toStart) => start(toStart)
      case StopConsuming(toStop)   => stop(toStop)

      case ConsumerClosed(e) =>
        logger.info("Consumer Closed")
        consumerRef foreach { context.stop }
        consumerRef = startNext()


      case Refresh =>
        logger.info(s"Refreshing")
        consumerRef match {
          case Some(ref) => ref ! CloseConsumer
          case None      => consumerRef = startNext()
        }

      case Metrics =>
        logger.info(s"Count: ${count}")
        consumerRef.foreach(_ ! Metrics)

      case Failure(e) => logger.info(s"Error from '${sender().path.name}'", e)
      case any        => logger.warn(s"Can't handle '$any' from '${sender().path.name}'")
    }
  }

  class ConsumerSupervisor (
    services : Services,
    parent   : ActorRef,
    consumer : KafkaConsumer[String, String],
    timeout  : java.time.Duration,
    handler  : ActorRef) extends Actor {

    val logger = LoggerFactory.getLogger(getClass)

    implicit val ec = services.ec()

    var shouldRun = true
    var count     = 0
    var loop      = 0
    var last      = Map.empty[String, Long]

    Future { consume } pipeTo parent

    override def receive = {
      case CloseConsumer => shouldRun = false;
      case Metrics       => printMetrics()
      case any           => logger.warn(s"[${getClass.getSimpleName}] Can handle $any")
    }

    def readRecords: Try[Unit] = {
      Try {
        val records = consumer.poll(timeout)
        logger.info(s"Processing ${records.count()} events from kafka")
        loop = loop + 1
        count = count + records.count()
        records.asScala foreach { record =>
          last = last + (record.topic() -> record.offset())
          handler ! record
        }
        consumer.commitSync
      }
    }

    def consume = {
      logger.info(s"Consumer Supervisor Started")
      while (shouldRun) {
        readRecords match {
          case Success(_) =>
          case Failure(e) =>
            logger.error("Error in 'consumer.poll'", e)
            shouldRun = false
        }
      }

      logger.info(s"Closing Consumer: ${last}")

      Try { consumer.close() } match {
        case Failure(e) =>
          logger.error("Error Closing Consumer", e)
          ConsumerClosed(Some(e))

        case Success(_) =>
          logger.info("Consumer Closed")
          ConsumerClosed(None)
      }
    }

    def printMetrics() = {
      logger.info(s"Count: $count")
      logger.info(s"Last: $last")
      logger.info("Metrics")
      consumer.metrics().asScala foreach {
        case (name, value) =>
          logger.info(s"[${name.group()}] ${name.name()} = ${value.metricValue()}")
      }
    }
  }

  class EventHandler(services: Services, handler: XinguKafkaEventHandler, storage: XinguKafkaStorage) extends Actor {

    private val logger = LoggerFactory.getLogger(getClass)

    private implicit val ec = services.ec()

    override def receive = {
      case record: ConsumerRecord[String, String] => process(record)
      case evt: Event                             => process(evt)
    }

    private def process(record: ConsumerRecord[String, String]) = {
      val topic     = record.topic()
      val offset    = record.offset()
      val value     = record.value()
      val key       = record.key()
      val partition = record.partition()
      logger.info(s"Record => topic:$topic, offset:$offset, key:$key, partition:$partition")
      Try {
        val json = Json.parse(value)
        ((json \ "evt").asOpt[String], json)
      } map {
        case (None, _)         => logger.warn(s"Not an event => topic:$topic, offset:$offset, key:$key, partition:$partition, value:$value")
        case (Some(evt), json) => self ! Event(topic, offset, partition, key, evt, json)
      }
    }

    private def process(event: Event) = {

      def handlePointer = {
        val pointer = event.value.as[KafkaMessagePointer]
        logger.info(s"Processing KafkaMessagePointer '${pointer.bucket}/${pointer.path}'")
        storage.download(pointer) map { res =>
          if(res.status == 200) {
            Try {
              val json = Json.parse(res.body)
              ((json \ "evt").asOpt[String], json)
            } map {
              case (None, _) =>
                logger.warn(s"Not an event => topic:${event.topic}, offset:${event.offset}, key:${event.key}, partition:${event.partition}, value:${res.body}")
              case (Some(evt), body) =>
                logger.info(s"KafkaMessagePointer '${pointer.bucket}/${pointer.path}' downloaded as $evt")
                self ! event.copy(kind = evt, value = body)
            }
          } else {
            logger.warn(s"Error processing KafkaMessagePointer '${pointer.bucket}/${pointer.path}'")
          }

        } recover {
          case NonFatal(e) =>
            logger.warn(s"Error processing KafkaMessagePointer: ${pointer.bucket}/${pointer.path}", e)
        }
      }

      logger.info(s"Processing event '${event.kind}' (${event.key})")

      if(event.kind == "KafkaMessagePointer") {
        handlePointer
      } else {
        handler.process(event)
      }
    }
  }

}

object route {

  import api._
  import org.slf4j.LoggerFactory
  import play.api.Configuration
  import play.api.libs.ws.{WSClient, WSResponse}
  import xingu.commons.play.services.Services

  import javax.inject.{Inject, Singleton}
  import scala.concurrent.duration._
  import scala.language.postfixOps
  import scala.util.control.NonFatal


  class Route(val to: String, selectors: Seq[EventSelector]) {
    def accepts(event: String): Boolean = {
      if(selectors.isEmpty) {
        false
      } else {
        selectors
          .map(_.accepts(event))
          .filter(_.isDefined)
          .map(_.get)
          .lastOption
          .getOrElse(false)
      }
    }
  }

  trait EventSelector {
    def accepts(event: String): Option[Boolean]
  }

  object IncludeAll extends EventSelector {
    override def accepts(event: String) = Some(true)
    override def toString = "IncludeAll"
  }

  case class Regex(value: String) extends EventSelector {
    val re = value.r
    override def accepts(event: String) = re.findFirstMatchIn(event).map(_ => true)
    override def toString = s"Regex: '$re'"
  }

  case class NotEquals(value: String) extends EventSelector {
    override def accepts(event: String) = if(value == event) Some(false) else None
    override def toString = s"Not Equals: '$value'"
  }

  case class Equals(value: String) extends EventSelector {
    override def accepts(event: String) = if(value == event) Some(true) else None
    override def toString = s"Equals: '$value'"
  }

  @Singleton
  class RouteToServiceEventHandler @Inject()(services: Services, ws: WSClient) extends XinguKafkaEventHandler {

    private val logger = LoggerFactory.getLogger(getClass)

    private val routes = routesGiven(services.conf().get[Seq[Configuration]]("xingu.kafka.consumer.routes"))

    private val timeout = 30 seconds

    private implicit val ec = services.ec()

    private def routesGiven(config: Seq[Configuration]): Seq[route.Route] = {

      def toRoute(c: Configuration): route.Route = {

        def toSelector(str: String): route.EventSelector = {
          str match {
            case "*"                              => route.IncludeAll
            case other if other.startsWith("re:") => route.Regex(str.substring(3))
            case other if other.startsWith("!")   => route.NotEquals(str.substring(1))
            case _                                => route.Equals(str)
          }
        }

        val target = c.get[String]("to")
        val events = c.get[Seq[String]]("events")
        new route.Route(target, events.map(toSelector))
      }

      config.map(toRoute)
    }

    override def process(event: Event) = {

      def push(url: String) = {
        ws
          .url(url)
          .withRequestTimeout(timeout)
          .post(event.value) map { res: WSResponse =>
          if(res.status == 200 || res.status == 204 /* Notification Service on port 9008 return 204 */) {
            logger.info(s"Pushed evt:${event.kind}, key:${event.key}, topic:${event.topic}, offset:${event.offset} to $url")
          } else {
            logger.error(s"Error Pushing evt:${event.kind}, key:${event.key}, topic:${event.topic}, offset:${event.offset} to $url: ${res.status} => ${res.body}")
          }
        } recover {
          case NonFatal(e) => logger.error(s"Push Error for evt:${event.kind}, url:$url, topic:${event.topic}, offset:${event.offset}", e)
        }
      }

      routes filter {
        _.accepts(event.kind)
      } foreach { route =>
        logger.info(s"Pushing event '${event.kind}' (${event.key}) to '${route.to}'")
        push(route.to)
      }
    }
  }

}
