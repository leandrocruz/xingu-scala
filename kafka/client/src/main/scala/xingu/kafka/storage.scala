package xingu.kafka.storage

object api {

  import play.api.libs.json.{JsSuccess, JsValue, Json, Reads}
  import play.api.libs.ws.WSResponse
  import xingu.kafka.Id
  import xingu.kafka.Message

  import scala.concurrent.Future

  case class KafkaMessagePointer(
    id          : String,
    bucket      : String,
    path        : String,
    kind        : String = "GCS",
    evt         : String = "KafkaMessagePointer"
  )

  case class UploadRequest(
    id          : String,
    bucket      : String,
    path        : String,
    callback    : String,
    contentType : Option[String] = None,
    data        : Option[String] = None,
    file        : Option[String] = None,
  )

  case class UploadResult(size: Long, uploaded: Long, contentType: String)
  case class DownloadRequest(bucket: String, path: String)
  case class UploadExchange(request: UploadRequest, result: Either[Throwable, UploadResult])

  /* Used for larga kafka messages */
  trait XinguKafkaStorage {
    def download(pointer: KafkaMessagePointer) : Future[WSResponse]
    def upload(msg: Message, id: Id)           : Future[WSResponse]
  }

  object json {

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
  import play.api.libs.ws.WSResponse
  import xingu.kafka.Id
  import xingu.kafka.Message

  import scala.concurrent.Future

  class FakeXinguKafkaStorage (response: WSResponse) extends XinguKafkaStorage {
    override def download(pointer: KafkaMessagePointer) = Future.successful(response)
    override def upload(msg: Message, id: Id)           = Future.failed(new Exception("Not Implemented Yet"))
  }

  class BadXinguKafkaStorage extends XinguKafkaStorage {
    override def download(pointer: KafkaMessagePointer) = Future.failed(new Exception("Not Implemented Yet"))
    override def upload(msg: Message, id: Id)           = Future.failed(new Exception("Not Implemented Yet"))
  }

}

object gcp {

  import api._
  import json._
  import org.slf4j.LoggerFactory
  import play.api.libs.json.Json
  import play.api.libs.ws.{WSClient, WSResponse}
  import xingu.commons.play.services.Services
  import xingu.kafka.Id
  import xingu.kafka.Message

  import java.nio.file.Paths
  import javax.inject.{Inject, Singleton}
  import scala.concurrent.Future
  import scala.concurrent.duration.Duration

  @Singleton
  class GCPProxyKafkaStorage @Inject()(services: Services, ws: WSClient)
    extends XinguKafkaStorage {

    private val logger = LoggerFactory.getLogger(getClass)

    private val conf     = services.conf()
    private val url      = conf.get[String]   ("xingu.kafka.storage.gcp-proxy.url")
    private val bucket   = conf.get[String]   ("xingu.kafka.storage.gcp-proxy.bucket")
    private val path     = conf.get[String]   ("xingu.kafka.storage.gcp-proxy.path")
    private val callback = conf.get[String]   ("xingu.kafka.storage.gcp-proxy.callback")
    private val download = conf.get[Duration] ("xingu.kafka.storage.gcp-proxy.timeout.download")
    private val upload   = conf.get[Duration] ("xingu.kafka.storage.gcp-proxy.timeout.upload")

    override def download(pointer: KafkaMessagePointer): Future[WSResponse] = {
      logger.info(s"Downloading Message from ${pointer.bucket}/${pointer.path}")
      ws
        .url(url + "/download")
        .withRequestTimeout(download)
        .post(Json.toJson(DownloadRequest(bucket = pointer.bucket, path = pointer.path)))
    }

    override def upload(msg: Message, id: Id): Future[WSResponse] = {
      val target  = Paths.get(path, id.dir, id.id)
      val request = UploadRequest(
        id       = id.id,
        bucket   = bucket,
        path     = target.toString,
        callback = callback,
        data     = Some(msg.payload)
      )

      logger.info(s"Uploading Message to ${url} (${bucket}/${target})")
      ws.url(url + "/upload")
        .withRequestTimeout(upload)
        .post(Json.toJson(request))
    }
  }
}


