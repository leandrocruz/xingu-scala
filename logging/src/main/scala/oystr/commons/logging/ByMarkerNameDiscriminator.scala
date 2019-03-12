package oystr.commons.logging

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.sift.AbstractDiscriminator

class ByMarkerNameDiscriminator extends AbstractDiscriminator[ILoggingEvent] {
  override def getKey: String = "markerName"
  override def getDiscriminatingValue(e: ILoggingEvent): String = e.getMarker.getName
}
