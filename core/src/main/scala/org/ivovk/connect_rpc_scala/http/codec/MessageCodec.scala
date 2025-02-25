package org.ivovk.connect_rpc_scala.http.codec

import org.http4s.{ContentCoding, DecodeResult, MediaType}
import org.ivovk.connect_rpc_scala.http.{RequestEntity, ResponseEntity}
import scalapb.{GeneratedMessage as Message, GeneratedMessageCompanion as Companion}

case class EncodeOptions(
  encoding: Option[ContentCoding]
)

object EncodeOptions {
  given Default: EncodeOptions = EncodeOptions(None)
}

trait MessageCodec[F[_]] {

  val mediaType: MediaType

  def decode[A <: Message](m: RequestEntity[F])(using cmp: Companion[A]): DecodeResult[F, A]

  def encode[A <: Message](message: A, options: EncodeOptions): ResponseEntity[F]

}
