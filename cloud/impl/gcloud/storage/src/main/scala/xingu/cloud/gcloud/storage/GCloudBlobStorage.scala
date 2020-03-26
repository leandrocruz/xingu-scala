package xingu.cloud.gcloud.storage

import java.io.{File, FileInputStream}
import java.nio.ByteBuffer
import java.nio.file.Files

import akka.util.ByteString
import com.google.auth.oauth2.ServiceAccountCredentials
import com.google.cloud.storage.Storage.{BlobListOption, BucketListOption}
import com.google.cloud.storage.{BlobInfo, Bucket, Storage, StorageOptions}
import javax.activation.MimetypesFileTypeMap
import javax.inject.{Inject, Singleton}
import org.slf4j.LoggerFactory
import play.api.Configuration
import resource.managed
import xingu.cloud.api.storage._

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}

class Impl(project: String, key: String) extends BlobStorage {

  private val logger = LoggerFactory.getLogger(getClass)

  private val WindowSize  = 1024 * 1024

  logger.info(s"Reading Service Account Credentials from '$key' (project: $project)")
  private val credentials = managed (new FileInputStream(new File(key))) acquireAndGet { ServiceAccountCredentials.fromStream }

  private val service: Storage = StorageOptions
    .newBuilder
    .setCredentials(credentials)
    .setProjectId(project)
    .build
    .getService

  private def findBucket(name: String)(implicit ec: ExecutionContext) = {
    Future {
      service.list(BucketListOption.prefix(name))
    } map {
      _.iterateAll().asScala.headOption
    }
  }

  override def list(bucket: String, path: String, size: Option[Long] = None)(implicit ec: ExecutionContext) = {

    def listItems(opt: Option[Bucket]) = {
      opt map { b =>
        Future {
          b.list(BlobListOption.prefix(path))
        } map {
          _.iterateAll().asScala.map(_.getName)
        }
      } getOrElse {
        Future.successful(Seq())
      }
    }

    for {
      obj   <- findBucket(bucket)
      items <- listItems(obj)
    } yield items.toSeq
  }

  override def upload(bucket: String, path: String, file: File, contentType: Option[String])(implicit ec: ExecutionContext): Future[UploadResult] = Future {

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

  override def download(bucket: String, path: String)(implicit ec: ExecutionContext) = {

    def downloadItem(opt: Option[Bucket]) = {
      opt map { b =>
        Future {
          b.get(path)
        } map { it =>
          Some(Blob(
            name        = it.getName,
            size        = it.getSize,
            contentType = Option(it.getContentType),
            data        = ByteString(it.getContent())))
        }
      } getOrElse {
        Future.successful(None)
      }
    }

    for {
      obj  <- findBucket(bucket)
      item <- downloadItem(obj)
    } yield item
  }
}

@Singleton
class GCloudBlobStorage @Inject() (config: Configuration) extends BlobStorage {

  private val conf    = config.get[Configuration]("xingu.cloud.storage.blob")
  private val project = conf.get[String]("project")
  private val key     = conf.get[String]("key")
  private val impl    = new Impl(project, key)

  override def list(bucket: String, path: String, size: Option[Long])(implicit ec: ExecutionContext) =
    impl.list(bucket, path, size)

  override def upload(bucket: String, path: String, file: File, contentType: Option[String])(implicit ec: ExecutionContext) =
    impl.upload(bucket, path, file, contentType)

  override def download(bucket: String, path: String)(implicit ec: ExecutionContext) =
    impl.download(bucket, path)
}