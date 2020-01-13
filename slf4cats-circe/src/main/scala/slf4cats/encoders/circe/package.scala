package slf4cats.encoders

import slf4cats.api.LogEncoder

package object circe {
  import io.circe.Encoder
  implicit def convertEncoder[A](implicit circeEncoder: Encoder[A]): LogEncoder[A] = (a: A) => circeEncoder(a).toString
}
