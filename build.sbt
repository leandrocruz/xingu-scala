import sbt.Keys._

ThisBuild / publishTo    := Some(GCSPublisher.forBucket("dogma-repo-test", AccessRights.InheritBucket))
ThisBuild / scalaVersion := "2.12.4"
ThisBuild / organization := "xingu"
ThisBuild / name         := "xingu-scala-commons"
ThisBuild / version      := "v1.0-SNAPSHOT"

lazy val settings = Seq(
  resolvers ++= Seq(
    "Local Maven Repository" at "file://" + Path.userHome.absolutePath + "/.m2/repository",
    Resolver.sonatypeRepo("releases"),
    Resolver.sonatypeRepo("snapshots")
  )
)

lazy val dependencies =
  new {
    val AkkaVersion   = "2.5.16"
    val logback       = "ch.qos.logback" % "logback-classic" % "1.2.3"
    val scalaArm      = "com.jsuereth"   %% "scala-arm"      % "2.0"
    val scalaTestPlus = "org.scalatestplus.play" %% "scalatestplus-play" % "3.1.0" % Test
}

lazy val commonDependencies = Seq(dependencies.scalaTestPlus)

lazy val commons = (project in file("commons"))
  .withId("xingu-scala-commons")
  .settings(settings)

lazy val logging = (project in file("logging"))
  .withId("xingu-scala-logging")
  .settings(
      settings, libraryDependencies ++= commonDependencies ++ Seq(dependencies.logback)
  )

lazy val play = (project in file("play"))
  .withId("xingu-scala-play")
  .enablePlugins(PlayScala)
  .disablePlugins(PlayLayoutPlugin)
  .dependsOn(commons)
  .settings(settings, libraryDependencies ++= commonDependencies ++ Seq(ws, dependencies.scalaArm))

lazy val xingu = (project in file("."))
    .aggregate(commons, logging, play)
    .settings(settings)
