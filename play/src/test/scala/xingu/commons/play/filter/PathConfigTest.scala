package xingu.commons.play.filter

import org.scalatest.{FlatSpecLike, Matchers}

class PathConfigTest extends FlatSpecLike with Matchers {
  it should "accept exact paths" in {
    new ExactMatch("/x").test("/x")   shouldBe true
    new ExactMatch("/x").test("/y")   shouldBe false
    new ExactMatch("/x").test("/x/y") shouldBe false
  }

  it should "accept regex paths" in {
    new RegexMatch("/x/.*")     .test("/x/y")    shouldBe true
    new RegexMatch("/x/.*")     .test("/x/y/x")  shouldBe true
    new RegexMatch("/x/\\w+")   .test("/x/y")    shouldBe true
    new RegexMatch("/x/[0-9]+") .test("/x/0")    shouldBe true
    new RegexMatch("/x/[0-9]+") .test("/x/y")    shouldBe false
    new RegexMatch("/x/.*")     .test("/x")      shouldBe false
  }
}

