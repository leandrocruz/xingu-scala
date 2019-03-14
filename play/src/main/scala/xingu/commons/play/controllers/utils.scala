package xingu.commons.play.controllers

import play.api.libs.json.JsError

import play.api.mvc.Results.BadRequest

object utils {
  implicit class JsonErrorHelper(err: JsError) {
    def toBadRequest() = {
      BadRequest(JsError.toJson(err))
    }
  }
}
