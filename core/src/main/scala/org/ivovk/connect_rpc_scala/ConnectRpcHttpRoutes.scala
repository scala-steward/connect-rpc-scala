package org.ivovk.connect_rpc_scala

import cats.data.EitherT
import cats.effect.Async
import cats.effect.kernel.Resource
import cats.implicits.*
import io.grpc.*
import io.grpc.MethodDescriptor.MethodType
import io.grpc.stub.MetadataUtils
import org.http4s.*
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.`Content-Type`
import org.json4s.jackson.JsonMethods
import scalapb.grpc.ClientCalls
import scalapb.json4s.JsonFormat
import scalapb.{GeneratedMessage, GeneratedMessageCompanion}

import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

case class Configuration(
  waitForShutdown: Duration = 10.seconds,
)

object ConnectRpcHttpRoutes {

  given [F[_] : Async, A <: GeneratedMessage](using cmp: GeneratedMessageCompanion[A]): EntityDecoder[F, A] with {
    override def decode(m: Media[F], strict: Boolean): DecodeResult[F, A] = {
      val f = m.body.through(fs2.io.toInputStream)
        .compile.resource.lastOrError
        .use { is =>
          Async[F].delay(JsonFormat.fromJson(JsonMethods.parse(is)))
        }

      EitherT(f.map(Right(_)))
    }

    override def consumes: Set[MediaRange] = Set(MediaRange.`application/*`)
  }

  given [F[_] : Async, A <: GeneratedMessage]: EntityEncoder[F, A] with {
    override def toEntity(a: A): Entity[F] =
      EntityEncoder.stringEncoder[F].toEntity(JsonFormat.toJsonString(a))

    override val headers: Headers =
      Headers(`Content-Type`(MediaType.application.`json`))
  }

  private def mkMetadata(headers: Headers): Metadata = {
    val metadata = new Metadata()
    headers.foreach { header =>
      metadata.put(Metadata.Key.of(header.name.toString, Metadata.ASCII_STRING_MARSHALLER), header.value)
    }
    metadata
  }

  private inline def grpcMethodName(serviceName: String, methodName: String): String =
    serviceName + "/" + methodName

  private case class RegistryEntry(
    requestMessageCompanion: GeneratedMessageCompanion[GeneratedMessage],
    methodDescriptor: MethodDescriptor[GeneratedMessage, GeneratedMessage],
  )

  def create[F[_] : Async](
    services: Seq[ServerServiceDefinition],
    configuration: Configuration = Configuration()
  ): Resource[F, HttpRoutes[F]] = {
    val dsl = Http4sDsl[F]
    import dsl.*

    val methodRegistry = services
      .flatMap(ssd => ssd.getMethods.asScala)
      .map(_.asInstanceOf[ServerMethodDefinition[GeneratedMessage, GeneratedMessage]])
      .filter(_.getMethodDescriptor.getType == MethodType.UNARY)
      .map { smd =>
        val methodDescriptor = smd.getMethodDescriptor

        val requestMarshaller = methodDescriptor.getRequestMarshaller match
          case m: scalapb.grpc.Marshaller[_] => m
          case tm: scalapb.grpc.TypeMappedMarshaller[_, _] => tm
          case unsupported => throw new RuntimeException(s"Unsupported marshaller $unsupported")

        val companionField = requestMarshaller.getClass.getDeclaredField("companion")
        companionField.setAccessible(true)

        val requestCompanion = companionField.get(requestMarshaller)
          .asInstanceOf[GeneratedMessageCompanion[GeneratedMessage]]

        val entry = RegistryEntry(
          requestMessageCompanion = requestCompanion,
          methodDescriptor = methodDescriptor,
        )

        methodDescriptor.getFullMethodName -> entry
      }
      .toMap

    for
      ipChannel <- InProcessChannelBridge.create(services, configuration.waitForShutdown)
    yield
      val httpApp = HttpRoutes.of[F] {
        case req@Method.POST -> Root / serviceName / methodName
          if methodRegistry.contains(grpcMethodName(serviceName, methodName)) =>
          val entry = methodRegistry(grpcMethodName(serviceName, methodName))

          given GeneratedMessageCompanion[GeneratedMessage] = entry.requestMessageCompanion

          req.as[GeneratedMessage]
            .flatMap { message =>
              val channel = ClientInterceptors.intercept(
                ipChannel,
                MetadataUtils.newAttachHeadersInterceptor(mkMetadata(req.headers))
              )

              Async[F].fromFuture(Async[F].delay {
                ClientCalls.asyncUnaryCall[GeneratedMessage, GeneratedMessage](
                  channel,
                  entry.methodDescriptor,
                  CallOptions.DEFAULT,
                  message
                )
              }).flatMap(Ok(_))
            }
            .recoverWith {
              case e: StatusRuntimeException =>
                val description = e.getStatus.getDescription

                e.getStatus.getCode match
                  case io.grpc.Status.Code.NOT_FOUND =>
                    NotFound(description)
                  case _ => // TODO: map other status codes
                    InternalServerError(description)
              case e: StatusException =>
                InternalServerError(e.getStatus.getDescription)
              case e: Throwable =>
                InternalServerError(e.getMessage)
            }
      }

      httpApp
  }

}
