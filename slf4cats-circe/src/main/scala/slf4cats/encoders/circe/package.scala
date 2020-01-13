package slf4cats.encoders

import io.circe.Printer
import slf4cats.api.LogEncoder

package object circe {
  import io.circe.Encoder
  implicit def convertEncoder[A](implicit circeEncoder: Encoder[A]): LogEncoder[A] = (a: A) => Printer.noSpaces.print(circeEncoder(a))
}
