package xingu.cloud.api.storage

import java.io.File

import akka.util.ByteString

import scala.concurrent.{ExecutionContext, Future}

case class UploadResult(
  size        : Long,
  uploaded    : Long,
  contentType : String
)

case class Blob(
  name        : String        ,
  size        : Long          ,
  contentType : Option[String],
  data        : ByteString
)

trait BlobStorage {
  def list     (bucket: String, path: String, size: Option[Long] = None)               (implicit ec: ExecutionContext): Future[Seq[String]]
  def upload   (bucket: String, path: String, file: File, contentType: Option[String]) (implicit ec: ExecutionContext): Future[UploadResult]
  def download (bucket: String, path: String)                                          (implicit ec: ExecutionContext): Future[Option[Blob]]
}