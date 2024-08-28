import sbt.Keys._
import sbt.Resolver

lazy val scala3   = "3.3.3"
lazy val scala212 = "2.12.19"
lazy val scala213 = "2.13.14"

//ThisBuild / publishTo    := Some(GCSPublisher.forBucket("dogma-repo-test", AccessRights.InheritBucket))
ThisBuild / scalaVersion := scala213
ThisBuild / organization := "xingu"
ThisBuild / version      := "v2.0.0-SNAPSHOT"
ThisBuild / crossScalaVersions := List(scala213)

lazy val settings = Seq(
  resolvers ++= Seq(
    Resolver.mavenLocal, //"Local Maven Repository" at "file://" + Path.userHome.absolutePath + "/.m2/repository",
    Resolver.bintrayRepo("cakesolutions", "maven"),
  ) ++ Resolver.sonatypeOssRepos("releases") ++ Resolver.sonatypeOssRepos("snapshots")
)

lazy val dependencies =
  new {
    val logback         = "ch.qos.logback"     %  "logback-classic"      % "1.5.6"
    val cats            = "org.typelevel"      %% "cats-core"            % "2.12.0"
    val shapeless       = "com.chuusai"        %% "shapeless"            % "2.3.12"
    val commonsLang     = "org.apache.commons" %  "commons-lang3"        % "3.15.0"
    val commonsIo       = "commons-io"         %  "commons-io"           % "2.16.1"
    val gcs             = "com.google.cloud"   %  "google-cloud-storage" % "2.42.0"
    val javaxActivation = "com.sun.activation" %  "javax.activation"     % "1.2.0"
    val kafkaClient     = "org.apache.kafka"   %  "kafka-clients"        % "3.8.0"
    val scalaTest       = "org.scalatest"      %% "scalatest"            % "3.2.19" % Test
    val scalaMock       = "org.scalamock"      %% "scalamock"            % "6.0.0"  % Test
}

lazy val commonDependencies = Seq(/*dependencies.javaxActivation,*/ dependencies.scalaTest, dependencies.scalaMock)

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
  .settings(settings, libraryDependencies ++= commonDependencies ++ Seq(ws))

lazy val cloudApi = (project in file("cloud/api"))
  .withId("xingu-cloud-api")
  .dependsOn(commons)
  .settings(settings, libraryDependencies ++= commonDependencies)

lazy val gcs = (project in file("cloud/impl/gcloud/storage"))
  .withId("xingu-cloud-gcs")
  .dependsOn(cloudApi)
  .settings(settings, libraryDependencies ++= commonDependencies ++ Seq(dependencies.gcs, dependencies.javaxActivation))

lazy val kafkaProducer = (project in file("kafka/producer"))
  .withId("xingu-kafka-producer")
  .enablePlugins(PlayScala)
  .disablePlugins(PlayLayoutPlugin)
  .dependsOn(commons)
  .settings(settings, libraryDependencies ++= commonDependencies ++ Seq(dependencies.kafkaClient))

lazy val kafkaClient = (project in file("kafka/client"))
  .withId("xingu-kafka-client")
  .dependsOn(play)
  .settings(settings, libraryDependencies ++= commonDependencies ++ Seq(dependencies.commonsLang, dependencies.cats, /*dependencies.shapeless,*/ dependencies.kafkaClient))

lazy val xingu = (project in file("."))
    .aggregate(commons, logging, play, cloudApi, gcs, kafkaProducer, kafkaClient)
    .settings(settings)
