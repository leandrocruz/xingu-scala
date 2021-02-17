import sbt.Keys._
import sbt.Resolver

//ThisBuild / publishTo    := Some(GCSPublisher.forBucket("dogma-repo-test", AccessRights.InheritBucket))
ThisBuild / scalaVersion := "2.12.11"
ThisBuild / organization := "xingu"
ThisBuild / name         := "xingu-scala-commons"
ThisBuild / version      := "v1.2.1"

lazy val settings = Seq(
  resolvers ++= Seq(
    "Local Maven Repository" at "file://" + Path.userHome.absolutePath + "/.m2/repository",
    Resolver.sonatypeRepo("releases"),
    Resolver.sonatypeRepo("snapshots"),
    Resolver.bintrayRepo("cakesolutions", "maven"),
  )
)

lazy val dependencies =
  new {
    val logback         = "ch.qos.logback" % "logback-classic" % "1.2.3"
    val scalaArm        = "com.jsuereth" %% "scala-arm" % "2.0"
    val gcs             = "com.google.cloud" % "google-cloud-storage" % "1.14.0"
    val scalaTestPlus   = "org.scalatestplus.play" %% "scalatestplus-play" % "3.1.2" % Test
    val scalaMock       = "org.scalamock" %% "scalamock-scalatest-support" % "3.5.0" % Test
    val javaxActivation = "com.sun.activation" % "javax.activation" % "1.2.0"
    val kafkaClient     = "net.cakesolutions" %% "scala-kafka-client-akka" % "2.3.1"
}

lazy val commonDependencies = Seq(dependencies.javaxActivation, dependencies.scalaTestPlus, dependencies.scalaMock)

lazy val commons = (project in file("commons"))
  .withId("xingu-commons")
  .settings(settings)

lazy val logging = (project in file("logging"))
  .withId("xingu-logging")
  .settings(
      settings, libraryDependencies ++= commonDependencies ++ Seq(dependencies.logback)
  )

lazy val play = (project in file("play"))
  .withId("xingu-scala-play")
  .enablePlugins(PlayScala)
  .disablePlugins(PlayLayoutPlugin)
  .dependsOn(commons)
  .settings(settings, libraryDependencies ++= commonDependencies ++ Seq(ws, dependencies.scalaArm))

lazy val cloudApi = (project in file("cloud/api"))
  .withId("xingu-cloud-api")
  .enablePlugins(PlayScala)
  .disablePlugins(PlayLayoutPlugin)
  .dependsOn(commons)
  .settings(settings, libraryDependencies ++= commonDependencies)

lazy val gcs = (project in file("cloud/impl/gcloud/storage"))
  .withId("xingu-cloud-gcs")
  .enablePlugins(PlayScala)
  .disablePlugins(PlayLayoutPlugin)
  .dependsOn(cloudApi)
  .settings(settings, libraryDependencies ++= commonDependencies ++ Seq(dependencies.gcs, dependencies.scalaArm))

lazy val kafkaProducer = (project in file("kafka/producer"))
  .withId("xingu-kafka-producer")
  .enablePlugins(PlayScala)
  .disablePlugins(PlayLayoutPlugin)
  .dependsOn(commons)
  .settings(settings, libraryDependencies ++= commonDependencies ++ Seq(dependencies.kafkaClient))

lazy val xingu = (project in file("."))
    .aggregate(commons, logging, play, cloudApi, gcs, kafkaProducer)
    .settings(settings)
