import akka.util.ByteString
import org.scalamock.scalatest.MockFactory
import org.scalatest.{FlatSpecLike, Matchers}
import play.api.libs.ws.WSResponse
import play.api.mvc.{Cookie, Cookies}
import xingu.commons.play.controllers.utils._


class ResponseHelperTest extends FlatSpecLike with Matchers with MockFactory {

  def baseMock() = {
    val response = mock[WSResponse]
    (response.status       _).expects().returning(200)
    (response.bodyAsBytes  _).expects().returning(ByteString.empty)
    (response.contentType  _).expects().returning("")
    response
  }

  it should "work with no cookies" in {
    val response = baseMock()
    (response.headers      _).expects().returning(Map.empty)
    (response.headerValues _).expects("Set-Cookie").returning(Seq.empty)
    val result = response.toResult
    result.newCookies.size shouldBe 0
  }

  it should "work with one cookie" in {
    val response = baseMock()
    (response.headers      _).expects().returning(Map("Set-Cookie" -> Seq("a=a")))
    (response.headerValues _).expects("Set-Cookie").returning(Seq("a=1; HTTPOnly"))
    val result = response.toResult
    result.newCookies should contain (Cookie("a", "1"))
  }

  it should "work with more than one cookie" in {
    val response = baseMock()
    (response.headers      _).expects().returning(Map("Set-Cookie" -> Seq("a=a", "b=b")))
    (response.headerValues _).expects("Set-Cookie").returning(Seq("a=1; HTTPOnly", "b=2; HTTPOnly"))
    val result = response.toResult
    result.newCookies should contain (Cookie("a", "1"))
    result.newCookies should contain (Cookie("b", "2"))
    result.newCookies.map(it => it.name + "=" + it.value) should contain theSameElementsAs Seq("a=1", "b=2")
  }

  it should "parse" in {
    Cookies.decodeSetCookieHeader("one=1; Path=/; HTTPOnly ;; two=2; Path=/; HTTPOnly").map(it => it.name + "=" + it.value) should contain theSameElementsAs Seq("one=1", "two=2")
  }
}