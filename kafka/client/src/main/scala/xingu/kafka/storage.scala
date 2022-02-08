package xingu.kafka

import org.slf4j.LoggerFactory

import java.nio.file.Paths
import xingu.kafka.api._
import xingu.kafka.api.json._
import play.api.Configuration
import play.api.libs.json.Json
import play.api.libs.ws.{WSClient, WSResponse}

import scala.concurrent.Future
import scala.concurrent.duration.Duration

object StorageClient {
  def apply(conf: Configuration, ws: WSClient) = {
    val url      = conf.get[String]   ("xingu.kafka.producer.storage.gcp-proxy.url")
    val bucket   = conf.get[String]   ("xingu.kafka.producer.storage.gcp-proxy.bucket")
    val path     = conf.get[String]   ("xingu.kafka.producer.storage.gcp-proxy.path")
    val callback = conf.get[String]   ("xingu.kafka.producer.storage.gcp-proxy.callback")
    val download = conf.get[Duration] ("xingu.kafka.producer.storage.gcp-proxy.timeout.download")
    val upload   = conf.get[Duration] ("xingu.kafka.producer.storage.gcp-proxy.timeout.upload")
    new StorageClient(ws, url, bucket, path, callback, download, upload)
  }
}

class StorageClient(ws: WSClient, url: String, bucket: String, path: String, callback: String, DownloadTimeout: Duration, UploadTimeout: Duration) {

  private val logger = LoggerFactory.getLogger(getClass)

  def download(pointer: KafkaMessagePointer): Future[WSResponse] = {
    logger.info(s"Downloading Message from ${pointer.bucket}/${pointer.path}")
    ws
      .url(url + "/download")
      .withRequestTimeout(DownloadTimeout)
      .post(Json.toJson(DownloadRequest(bucket = pointer.bucket, path = pointer.path)))
  }

  def upload(msg: Message, id: Id): Future[WSResponse] = {
    val target  = Paths.get(path, id.dir, id.id)
    val request = UploadRequest(
      id       = id.id,
      bucket   = bucket,
      path     = target.toString,
      callback = callback,
      data     = Some(msg.payload)
    )

    logger.info(s"Uploading Message to ${url} (${bucket}/${target})")
    ws.url(url + "/upload").withRequestTimeout(UploadTimeout).post(Json.toJson(request))
  }
}
