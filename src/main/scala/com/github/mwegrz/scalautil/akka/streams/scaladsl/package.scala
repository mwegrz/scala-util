package com.github.mwegrz.scalautil.akka.streams

import java.net.InetSocketAddress
import akka.stream.FlowShape
import akka.{ Done, NotUsed }
import akka.stream.scaladsl.{ Broadcast, Flow, GraphDSL, Keep, Sink, Source }
import akka.util.ByteString
import org.scalactic.{ Bad, Good, Or }
import scala.concurrent.Future
import scala.util.{ Failure, Success, Try }

package object scaladsl {
  type UdpFlow = Flow[(ByteString, InetSocketAddress), (ByteString, InetSocketAddress), NotUsed]

  implicit class FlowOps[A, B, C](flow: Flow[A, B, C]) {
    def toSource: Source[B, C] = Source.empty.viaMat(flow)(Keep.right)

    def toSink: Sink[A, C] = flow.toMat(Sink.ignore)(Keep.left)
  }

  implicit class OrFlowOps[A, B, C, D](flow: Flow[A, Or[B, C], D]) {
    def filterGood(badSink: Sink[Bad[C], Future[Done]]): Flow[A, B, D] = {
      val filter = GraphDSL.create() { implicit b =>
        import GraphDSL.Implicits._

        val broadcastShape = b.add(Broadcast[Or[B, C]](2))
        val badSinkShape = b.add(badSink)
        val goodFilterShape = b.add(
          Flow[Or[B, C]]
            .collect {
              case e: Good[B] => e
            }
            .map(_.get))
        val badFilterShape = b.add(Flow[Or[B, C]].collect {
          case e: Bad[C] => e
        })

        broadcastShape.out(1) ~> badFilterShape ~> badSinkShape
        broadcastShape.out(0) ~> goodFilterShape

        FlowShape(broadcastShape.in, goodFilterShape.out)
      }

      flow.via(filter)
    }
  }

  implicit class TryFlowOps[A, B, C](flow: Flow[A, Try[B], C]) {
    def filterSuccess(failureSink: Sink[Failure[B], Future[Done]]): Flow[A, B, C] = {
      val filter = GraphDSL.create() { implicit b =>
        import GraphDSL.Implicits._

        val broadcastShape = b.add(Broadcast[Try[B]](2))
        val failureSinkShape = b.add(failureSink)
        val successFilterShape = b.add(
          Flow[Try[B]]
            .collect {
              case e: Success[B] => e
            }
            .map(_.get))
        val failureFilterShape = b.add(Flow[Try[B]].collect {
          case e: Failure[B] => e
        })

        broadcastShape.out(1) ~> failureFilterShape ~> failureSinkShape
        broadcastShape.out(0) ~> successFilterShape

        FlowShape(broadcastShape.in, successFilterShape.out)
      }

      flow.via(filter)
    }
  }

  implicit class EitherFlowOps[A, B, C, D](flow: Flow[A, Either[B, C], D]) {
    def filterSuccess(leftSink: Sink[Left[B, C], Future[Done]]): Flow[A, B, D] = {
      val filter = GraphDSL.create() { implicit b =>
        import GraphDSL.Implicits._

        val broadcastShape = b.add(Broadcast[Either[B, C]](2))
        val leftSinkShape = b.add(leftSink)
        val rightFilterShape = b.add(
          Flow[Either[B, C]]
            .collect {
              case e: Right[A, B] => e
            }
            .map(_.value))
        val leftFilterShape = b.add(Flow[Either[B, C]].collect {
          case e: Left[B, C] => e
        })

        broadcastShape.out(1) ~> leftFilterShape ~> leftSinkShape
        broadcastShape.out(0) ~> rightFilterShape

        FlowShape(broadcastShape.in, rightFilterShape.out)
      }

      flow.via(filter)
    }
  }
}
