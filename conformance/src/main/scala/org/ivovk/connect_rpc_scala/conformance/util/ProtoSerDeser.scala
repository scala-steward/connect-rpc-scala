package org.ivovk.connect_rpc_scala.conformance.util

import cats.effect.Sync
import scalapb.{GeneratedMessage as Message, GeneratedMessageCompanion as Companion}

import java.io.{InputStream, OutputStream}

object ProtoSerDeser {
  def systemInOut[F[_]: Sync]: ProtoSerDeser[F] =
    new ProtoSerDeser[F](System.in, System.out)
}

class ProtoSerDeser[F[_]: Sync](
  in: InputStream,
  out: OutputStream,
) {
  def read[T <: Message](using comp: Companion[T]): F[T] =
    Sync[F].delay {
      val size = IntSerDeser.read(in)
      comp.parseFrom(in.readNBytes(size))
    }

  def write(msg: Message): F[Unit] =
    Sync[F].delay {
      IntSerDeser.write(out, msg.serializedSize)
      out.flush()
      out.write(msg.toByteArray)
      out.flush()
    }
}
