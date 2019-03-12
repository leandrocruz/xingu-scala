package xingu.commons.play

import java.io.{File, FileInputStream, InputStream}

import play.api.Configuration
import resource._

object config {
  implicit class ConfigurationHelper(conf: Configuration) {

    def withConfig[R](key: String)(handler: Configuration => R) =
      conf.getOptional[Configuration](key) match {
        case None =>
          throw new Exception(s"Can't find configuration for '${key}'")
        case Some(conf) =>
          handler(conf)
      }


    def withKey[R](key: String)(handler: String => R) =
      handler(conf.get[String](key))

    def withFile[R](key: String)(handler: InputStream => R) =
      withKey(key) { name =>
        val file = new File(name)
        if(!file.exists())
          throw new Exception(s"Can't find file '${name}'")
        else
          managed(new FileInputStream(file)) acquireAndGet { handler }
      }
  }
}