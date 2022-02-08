package xingu.kafka

object api {

  import play.api.libs.json.{JsSuccess, JsValue, Json, Reads}

  import scala.concurrent.Future

  case class Message(topic: String, payload: String, key: Option[String] = None)

  case class KafkaMessagePointer(
    id          : String,
    bucket      : String,
    path        : String,
    kind        : String = "GCS",
    evt         : String = "KafkaMessagePointer"
  )

  case class Produced(partition: Int, offset: Long)

  case class UploadRequest(
    id          : String,
    bucket      : String,
    path        : String,
    callback    : String,
    contentType : Option[String] = None,
    data        : Option[String] = None,
    file        : Option[String] = None,
  )

  case class UploadResult(
    size        : Long,
    uploaded    : Long,
    contentType : String
  )

  case class DownloadRequest(
    bucket : String,
    path   : String,
  )

  case class UploadExchange(request: UploadRequest, result: Either[Throwable, UploadResult])

  trait MessageDispatcher {
    def send(msg: Message): Future[Either[Throwable, Produced]]
  }

  object json {
    implicit val MessageReader             = Json.reads  [Message]
    implicit val ProducedWriter            = Json.writes [Produced]
    implicit val DownloadRequestWriter     = Json.writes [DownloadRequest]
    implicit val UploadRequestReader       = Json.reads  [UploadRequest]
    implicit val UploadRequestWriter       = Json.writes [UploadRequest]
    implicit val UploadResultReader        = Json.reads  [UploadResult]
    implicit val UploadResultWriter        = Json.writes [UploadResult]
    implicit val KafkaMessagePointerReader = Json.reads  [KafkaMessagePointer]
    implicit val KafkaMessagePointerWriter = Json.writes [KafkaMessagePointer]

    implicit val UploadExchangeReader = new Reads[UploadExchange] {
      override def reads(json: JsValue) = {
        val result: Either[Throwable, UploadResult] = (json \ "result" \ "error").asOpt[String] match {
          case Some(error) => Left(new Exception(error))
          case None => Right((json \ "result").as[UploadResult])
        }
        val request = (json \ "request").as[UploadRequest]
        JsSuccess(UploadExchange(request, result))
      }
    }
  }
}

object impl {
  import api._
  import cats.data.EitherT
  import cats.implicits._
  import org.apache.kafka.clients.producer.RecordMetadata
  import org.slf4j.LoggerFactory
  import xingu.commons.play.services.Services

  import javax.inject.{Inject, Singleton}
  import scala.concurrent.Future
  @Singleton
  class KafkaMessageDispatcher @Inject()(services: Services) extends MessageDispatcher {

    import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord}
    import java.util.Properties
    import scala.util.Try

    implicit val executor = services.ec()

    val logger         = LoggerFactory.getLogger(getClass)
    val conf           = services.conf()
    val clock          = services.clock()
    val enabled        = conf.getOptional[Boolean]("dispatcher.enabled").getOrElse(true)
    val servers        = conf.get[String]         ("dispatcher.servers")
    val key            = conf.get[String]         ("dispatcher.key")
    val secret         = conf.get[String]         ("dispatcher.secret")

    logger.info(s"Kafka Settings:\n- servers: $servers\n- key: $key")

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
        "sasl.mechanism"                        -> "PLAIN"
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

  import akka.actor.{Actor, ActorRef}
  import akka.pattern.pipe
  import api.json._
  import org.slf4j.LoggerFactory
  import xingu.kafka.api.{KafkaMessagePointer, Message, MessageDispatcher, UploadExchange}
  import xingu.kafka.{Id, StorageClient}
  import play.api.libs.json.Json
  import play.api.libs.ws.{WSClient, WSResponse}
  import xingu.commons.play.services.Services

  import javax.inject.Inject
  import scala.util.control.NonFatal

  class DispatchSupervisor @Inject() (services: Services, ws: WSClient, dispatcher: MessageDispatcher) extends Actor {

    private implicit val ec = services.ec()
    private val logger      = LoggerFactory.getLogger(getClass)
    private val conf        = services.conf()
    private val clock       = services.clock()
    private val threshold   = conf.get[Int]("dispatcher.threshold")
    private val storage     = StorageClient(conf, ws)
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
        dispatcher.send(msg) pipeTo sender
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
                dispatcher.send(Message(topic, Json.stringify(payload) , key)) pipeTo ref
            }
        }
    }
  }
}
