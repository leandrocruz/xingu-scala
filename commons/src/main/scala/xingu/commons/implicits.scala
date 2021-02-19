package xingu.commons

import java.time.LocalDateTime

object implicits {
  implicit val localDateOrdering: Ordering[LocalDateTime] = _ compareTo _
}