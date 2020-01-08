package slf4cats.encoders

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import slf4cats.api.LogEncoder

package object jackson {
  val jackson = new ObjectMapper()

  implicit def encoder[A]: LogEncoder[A] = {
    jackson.registerModule(DefaultScalaModule)
    jackson.writeValueAsString
  }
}
