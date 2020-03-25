package xingu.cloud.api.storage

import java.io.File

import scala.concurrent.{ExecutionContext, Future}

case class UploadResult(
  size        : Long,
  uploaded    : Long,
  contentType : String
)

trait BlobStorage {
  def upload(file: File, bucket: String, path: String, contentType: Option[String])(implicit ec: ExecutionContext): Future[UploadResult]
}