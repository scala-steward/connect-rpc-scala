package org.ivovk.connect_rpc_scala.netty

import cats.effect.Async
import cats.effect.std.Dispatcher
import cats.implicits.*
import fs2.{Chunk, Stream}
import io.netty.buffer.{ByteBufUtil, Unpooled}
import io.netty.channel.{ChannelFuture, ChannelHandlerContext, ChannelInboundHandlerAdapter}
import io.netty.handler.codec.http.*
import io.netty.util.concurrent.GenericFutureListener
import org.http4s.MediaType
import org.ivovk.connect_rpc_scala.HeaderMapping
import org.ivovk.connect_rpc_scala.grpc.MethodRegistry
import org.ivovk.connect_rpc_scala.http.codec.{MessageCodec, MessageCodecRegistry}
import org.ivovk.connect_rpc_scala.http.{MediaTypes, RequestEntity}
import org.ivovk.connect_rpc_scala.netty.connect.ConnectHandler
import org.slf4j.LoggerFactory

class ConnectHttpServerHandlerFactory[F[_]: Async](
  dispatcher: Dispatcher[F],
  methodRegistry: MethodRegistry,
  headerMapping: HeaderMapping[HttpHeaders],
  codecRegistry: MessageCodecRegistry[F],
  connectHandler: ConnectHandler[F],
) {
  def createHandler() =
    new HttpServerHandler[F](
      dispatcher = dispatcher,
      methodRegistry = methodRegistry,
      headerMapping = headerMapping,
      codecRegistry = codecRegistry,
      connectHandler = connectHandler,
    )
}

class HttpServerHandler[F[_]: Async](
  dispatcher: Dispatcher[F],
  methodRegistry: MethodRegistry,
  headerMapping: HeaderMapping[HttpHeaders],
  codecRegistry: MessageCodecRegistry[F],
  connectHandler: ConnectHandler[F],
) extends ChannelInboundHandlerAdapter {

  private val logger = LoggerFactory.getLogger(getClass)

  override def channelReadComplete(ctx: ChannelHandlerContext): Unit = {
    ctx.flush()
    super.channelReadComplete(ctx)
  }

  override def channelRead(ctx: ChannelHandlerContext, msg: AnyRef): Unit =
    msg match {
      case request: FullHttpRequest =>
        if (logger.isTraceEnabled) {
          logger.trace(s">>> HTTP request: ${request.method()} ${request.uri()}")
          logger.trace(s">>> Headers: ${request.headers()}")
        }

        val isGet = request.method() == HttpMethod.GET

        val decodedUri = QueryStringDecoder(request.uri())
        val pathParts  = decodedUri.path().substring(1).split('/').toList

        val grpcMethod = pathParts match {
          case serviceName :: methodName :: Nil =>
            methodRegistry.get(serviceName, methodName)
          case _ =>
            None
        }

        val mediaType =
          if isGet then
            Option(decodedUri.parameters().get("encoding")).map(_.getFirst()) match {
              case Some("json")     => MediaTypes.`application/json`
              case Some("protobuf") => MediaTypes.`application/proto`
              case _                => MediaTypes.`application/json`
            }
          else
            Option(request.headers().get(HttpHeaderNames.CONTENT_TYPE))
              .flatMap(MediaType.parse(_).toOption)
              .getOrElse(MediaTypes.`application/json`)

        given MessageCodec[F] = codecRegistry.byMediaType(mediaType).get

        val responseF = grpcMethod match {
          case None =>
            val response = new DefaultFullHttpResponse(
              HttpVersion.HTTP_1_1,
              HttpResponseStatus.BAD_REQUEST,
              Unpooled.wrappedBuffer("Method not found".getBytes),
            )
            response.headers()
              .set(HttpHeaderNames.CONTENT_TYPE, "text/plain; charset=UTF-8")
              .set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes())

            response.pure[F]
          case Some(methodEntry) =>
            val message: String | Stream[F, Byte] =
              if isGet then Option(decodedUri.parameters().get("message")).map(_.getFirst()).getOrElse("")
              else Stream.chunk(Chunk.array(ByteBufUtil.getBytes(request.content())))

            val requestEntity = RequestEntity[F](
              message = message,
              headers = headerMapping.toMetadata(request.headers()),
            )

            connectHandler.handle(requestEntity, methodEntry)
        }

        val f = for
          response <- responseF
          _        <- async(ctx.writeAndFlush(response))
          _ = ctx.fireChannelReadComplete()
        yield ()

        dispatcher.unsafeRunAndForget(f)
    }

  private def async(cf: => ChannelFuture): F[Unit] =
    Async[F].async { cb =>
      Async[F].delay {
        cf.addListener((f: ChannelFuture) =>
          if (f.isSuccess) {
            cb(Right(()))
          } else {
            cb(Left(f.cause()))
          }
        )

        None
      }
    }

  override def exceptionCaught(ctx: ChannelHandlerContext, cause: Throwable): Unit = {
    logger.error("Fatal exception caught", cause)
    ctx.close()
  }

}
