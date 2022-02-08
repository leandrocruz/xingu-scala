package xingu.kafka.producer

import javax.inject.{Inject, Singleton}
import org.slf4j.LoggerFactory
import play.api.Configuration
import play.api.libs.json.{JsNull, JsString, JsValue, Json}
import scala.concurrent.{ExecutionContext, Future}

case class MessageProduced(offset: Long, partition: Int)

trait KafkaProducerWrapper {
  def send(topic: String, msg: String, key: Option[String] = None): Future[Either[Throwable, MessageProduced]]
}

@Singleton
class SimpleKafkaProducer @Inject()(conf: Configuration, ec: ExecutionContext) extends KafkaProducerWrapper {

  import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord}
  import java.util.Properties
  import scala.util.Try

  implicit val executor = ec

  val logger  = LoggerFactory.getLogger(getClass)
  val enabled = conf.getOptional[Boolean]("kafka-producer.enabled").getOrElse(true)
  val servers = conf.get[String]("kafka-producer.servers")
  val key     = conf.get[String]("kafka-producer.key")
  val secret  = conf.get[String]("kafka-producer.secret")

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

  override def send(topic: String, msg: String, key: Option[String] = None) = {
    val payload = Json.obj(
      "topic" -> topic,
      "msg" -> Json.parse(msg),
      "key" -> key.map(v => JsString(v)).getOrElse(JsNull).as[JsValue]
    )

    logger.info(s"Dispatching event: ($key) '$msg' to '$topic' [${producer.map(_ => "active").getOrElse("not active")}]")

    producer match {
      case Some(it) =>
        val record: ProducerRecord[String, String] = key match {
          case Some(k) => new ProducerRecord(topic, k, msg)
          case None    => new ProducerRecord(topic, msg)
        }

        Future {
          Try(it.send(record).get()).toEither
        } map {
          case Left(error) => Left(error)
          case Right(meta) => Right(MessageProduced(meta.offset(), meta.partition()))
        }

      case None =>
        Future.successful { Right(MessageProduced(-1, -1)) }
    }
  }
}