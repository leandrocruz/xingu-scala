import java.math.BigInteger
import java.security.MessageDigest
import scala.concurrent.Future

object utils {
  implicit class HashUtils(input: String) {
    def sha256() =
      String.format("%032x", new BigInteger(1, MessageDigest.getInstance("SHA-256").digest(input.getBytes("UTF-8"))))
  }

  implicit class FutureUtils[T](obj: T) {
    def successful() = Future.successful(obj)
  }
}