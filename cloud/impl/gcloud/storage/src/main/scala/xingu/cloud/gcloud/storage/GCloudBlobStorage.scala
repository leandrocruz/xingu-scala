package xingu.cloud.gcloud.storage

import java.io.{File, FileInputStream}
import java.nio.ByteBuffer
import java.nio.file.Files

import com.google.auth.oauth2.ServiceAccountCredentials
import com.google.cloud.storage.{BlobInfo, StorageOptions}
import javax.activation.MimetypesFileTypeMap
import javax.inject.{Inject, Singleton}
import org.slf4j.LoggerFactory
import play.api.Configuration
import xingu.cloud.api.storage._
import resource.managed
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class GCloudBlobStorage @Inject() (config: Configuration) extends BlobStorage {

  val logger = LoggerFactory.getLogger(getClass)

  val WindowSize  = 1024 * 1024
  val key         = config.get[String]("xingu.cloud.storage.blob.key")
  val credentials = managed (new FileInputStream(new File(key))) acquireAndGet { ServiceAccountCredentials.fromStream }

  val service = StorageOptions
    .newBuilder
    .setCredentials(credentials)
    .build
    .getService

  override def upload(file: File, bucket: String, path: String, contentType: Option[String])(implicit ec: ExecutionContext): Future[UploadResult] = Future {

    val ctype  = contentType.getOrElse(new MimetypesFileTypeMap().getContentType(file))
    logger.info(s"Uploading file to '$path' as '$ctype'")

    val info = BlobInfo
      .newBuilder(bucket, path)
      .setContentType(ctype)
      .build()

    var total     = 0
    var available = 0
    val buffer    = new Array[Byte](WindowSize)
    val input     = Files.newInputStream(file.toPath)
    val writer    = service.writer(info)

    while ({
      available = input.read(buffer)
      available >= 0
    }) {
      total = total + available
      writer.write(ByteBuffer.wrap(buffer, 0, available))
    }
    writer.close()

    UploadResult(
      size        = Files.size(file.toPath),
      uploaded    = total,
      contentType = ctype
    )
  }
}