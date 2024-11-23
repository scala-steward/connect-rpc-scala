package org.ivovk.connect_rpc_scala

import cats.effect.*
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import io.grpc.ServerServiceDefinition
import org.http4s.client.Client
import org.http4s.implicits.*
import org.http4s.{Method, *}
import org.ivovk.connect_rpc_scala.test.TestService.TestServiceGrpc.TestService
import org.ivovk.connect_rpc_scala.test.TestService.{AddRequest, AddResponse}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

class HttpTest extends AnyFunSuite, Matchers {

  object TestServiceImpl extends TestService {
    override def add(request: AddRequest): Future[AddResponse] =
      Future.successful(AddResponse(request.a + request.b))
  }



  test("basic") {
    val services: Seq[ServerServiceDefinition] = Seq(
      TestService.bindService(TestServiceImpl, ExecutionContext.global)
    )

    val httpApp = ConnectRpcHttpRoutes.make[IO](services.toList).orNotFound

    val client = Client.fromHttpApp(httpApp)
    client
      .run(
        Request[IO](Method.POST, uri"/org.ivovk.connect_rpc_scala.test.TestService/Add")
          .withEntity(""" { "a": 1, "b": 2} """)
      )
      .use { response =>
        for {
          body <- response.as[String]
          status <- response.status.pure[IO]
        } yield {
          assert(status == Status.Ok)
          assert(body == """{"sum":3}""")
        }
      }
      .unsafeRunSync()
  }

}
