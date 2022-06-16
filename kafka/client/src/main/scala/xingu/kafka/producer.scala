package xingu.kafka.producer

object api {

  import play.api.libs.json.Json
  import xingu.kafka.Message

  import scala.concurrent.Future

  case class Produced(partition: Int, offset: Long)

  trait XinguKafkaProducer {
    def send(msg: Message): Future[Either[Throwable, Produced]]
  }

  object json {
    implicit val ProducedWriter = Json.writes [Produced]
  }
}

object impl {

  import xingu.kafka.Message
  import api._
  import cats.data.EitherT
  import cats.implicits._
  import org.apache.kafka.common.serialization.StringSerializer
  import org.apache.kafka.clients.producer.RecordMetadata
  import org.slf4j.LoggerFactory
  import xingu.commons.play.services.Services
  import javax.inject.{Inject, Singleton}
  import scala.concurrent.Future

  @Singleton
  class SimpleXinguKafkaProducer @Inject()(services: Services) extends XinguKafkaProducer {

    import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord}

    import java.util.Properties
    import scala.util.Try

    implicit val executor = services.ec()

    val logger         = LoggerFactory.getLogger(getClass)
    val conf           = services.conf()
    val clock          = services.clock()
    val enabled        = conf.getOptional[Boolean]("xingu.kafka.producer.enabled").getOrElse(true)
    val servers        = conf.get[String]         ("xingu.kafka.servers")
    val key            = conf.get[String]         ("xingu.kafka.key")
    val secret         = conf.get[String]         ("xingu.kafka.secret")

    logger.info(
      s"""Kafka Producer Config:
         | enabled : $enabled
         | servers : $servers
         | key     : $key""".stripMargin)

    /*
      See:
      - https://docs.confluent.io/current/cloud/using/config-client.html
      - https://github.com/confluentinc/examples/tree/5.5.0-post/clients/cloud/java
      - https://github.com/confluentinc/configuration-templates/blob/master/README.md
     */
    private val producer = if(enabled) {
      val cfg = Map(
        "bootstrap.servers"                     -> servers,
        "security.protocol"                     -> "SASL_SSL",
        "sasl.jaas.config"                      -> s"""org.apache.kafka.common.security.plain.PlainLoginModule required username="$key" password="$secret";""",
        "ssl.endpoint.identification.algorithm" -> "https",
        "sasl.mechanism"                        -> "PLAIN",
        "key.serializer"                        -> classOf[StringSerializer].getName,
        "value.serializer"                      -> classOf[StringSerializer].getName
      )

      val props = new Properties()
      cfg.foreach { case (key, value) => props.setProperty(key, value) }
      Some(new KafkaProducer[String, String](props))
    } else {
      None
    }

    override def send(msg: Message) = {

      def build: Future[Either[Throwable, KafkaProducer[String, String]]] = {
        Future successful {
          producer.map(Right(_)).getOrElse(Left(new Exception("Producer is not enabled")))
        }
      }

      def toRecord: Future[Either[Throwable, ProducerRecord[String, String]]] = {
        Future successful {
          val record: ProducerRecord[String, String] = msg.key match {
            case Some(key) => new ProducerRecord(msg.topic, key, msg.payload)
            case None      => new ProducerRecord(msg.topic, msg.payload)
          }
          Right(record)
        }
      }

      def push(sender: KafkaProducer[String, String], record: ProducerRecord[String, String]): Future[Either[Throwable, RecordMetadata]] = {
        Future {
          Try(sender.send(record).get()).toEither
        }
      }

      def toProduced(meta: RecordMetadata): Produced = {
        Produced(
          partition = meta.partition(),
          offset = meta.offset(),
        )
      }

      logger.info(s"Dispatching event: (${msg.key}) '$msg' to '${msg.topic}' [${producer.map(_ => "active").getOrElse("not active")}]")

      val tmp = for {
        sender <- EitherT { build                }
        record <- EitherT { toRecord             }
        result <- EitherT { push(sender, record) }
      } yield toProduced(result)

      tmp.value

    }
  }
}

object supervisor {

  import xingu.kafka.Message
  import xingu.kafka.Id
  import akka.actor.{Actor, ActorRef}
  import akka.pattern.pipe
  import org.slf4j.LoggerFactory
  import play.api.libs.json.Json
  import play.api.libs.ws.WSResponse
  import xingu.commons.play.services.Services
  import xingu.kafka.producer.api._
  import xingu.kafka.storage.api._
  import xingu.kafka.storage.api.json._

  import javax.inject.Inject
  import scala.util.control.NonFatal

  class DispatchSupervisor @Inject() (services: Services, producer: XinguKafkaProducer, storage: XinguKafkaStorage) extends Actor {

    private implicit val ec = services.ec()
    private val logger      = LoggerFactory.getLogger(getClass)
    private val conf        = services.conf()
    private val clock       = services.clock()
    private val threshold   = conf.get[Int]("xingu.kafka.producer.threshold")
    private var buffer      = Map.empty[String, (ActorRef, String, Option[String])]

    private def send(msg: Message) = {

      def handleUpload(replyTo: ActorRef)(response: WSResponse): Unit = {
        if (response.status != 200) {
          replyTo ! Left(new Exception(s"GCP Proxy returned ${response.status}"))
        }
      }

      if (msg.payload.length > threshold) {
        val replyTo = sender
        val id      = Id.gen(clock)
        buffer      = buffer + (id.id -> (sender, msg.topic, msg.key))
        logger.info(s"Uploading Kafka Message (${msg.topic}/${msg.key.getOrElse("None")}) '${id.id}' with ${msg.payload.length} bytes to blob storage")
        storage
          .upload(msg, id)
          .map(handleUpload(replyTo))
          .recover({
            case NonFatal(e) =>
              logger.warn("Error uploading", e)
              replyTo ! Left(e)
          })
      } else {
        logger.info(s"Sending Kafka Message Directly (${msg.topic}/${msg.key.getOrElse("None")})")
        producer.send(msg) pipeTo sender
      }
    }

    override def receive = {
      case it: Message => send(it)
      case UploadExchange(request, response) =>
        /* Called from GCP Proxy */
        sender ! Right(())
        buffer.get(request.id) match {
          case None      => /* Daemon Restarted?? */
            logger.warn(s"Can't find original message for ${request.id}. File was uploaded to ${request.bucket}/${request.path}")
          case Some((ref, topic, key)) =>
            response match {
              case Left(err) =>
                logger.error(s"Error uploading Kafka Message '${request.id}' to ${request.bucket}/${request.path}", err)
                ref ! Left(err)
              case Right(_)  =>
                logger.info(s"Kafka Message '${request.id}' uploaded to ${request.bucket}/${request.path}")
                val payload = Json toJson {
                  KafkaMessagePointer(
                    id     = request.id,
                    bucket = request.bucket,
                    path   = request.path
                  )
                }
                producer.send(Message(topic, Json.stringify(payload) , key)) pipeTo ref
            }
        }
    }
  }
}
