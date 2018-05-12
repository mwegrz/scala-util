package com.github.mwegrz.scalautil.akka.http.server.directives.routes

import java.time.Instant

import akka.NotUsed
import akka.http.scaladsl.marshalling.{ Marshal, ToEntityMarshaller, ToResponseMarshaller }
import akka.http.scaladsl.model.sse.ServerSentEvent
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.stream.{ KillSwitches, Materializer, OverflowStrategy }
import akka.stream.scaladsl.{ Keep, Source }
import com.github.mwegrz.app.Shutdownable
import com.github.mwegrz.scalautil.store.TimeSeriesStore
import akka.http.scaladsl.marshalling.sse.EventStreamMarshalling._
import akka.http.scaladsl.model.MessageEntity
import com.github.mwegrz.scalastructlog.KeyValueLogging
import scodec.bits.ByteVector

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext
import scala.reflect.ClassTag
import scala.util.Try

object TimeSeriesStoreSource {
  private val LiveValuesBufferSize = 1000
}

class TimeSeriesStoreSource[Key, Value: ClassTag](valueStore: TimeSeriesStore[Key, Value],
                                                  valueSource: Source[(Key, Instant, Value), NotUsed])(
    implicit
    instantFromStringUnmarshaller: Unmarshaller[String, Instant],
    valueToEntityMarshaller: ToEntityMarshaller[Value],
    valueSourceToResponseMarshaller: ToResponseMarshaller[Source[Value, NotUsed]],
    executionContext: ExecutionContext,
    materializer: Materializer)
    extends Shutdownable
    with KeyValueLogging {
  import TimeSeriesStoreSource._

  private val valueTypeName = implicitly[ClassTag[Value]].runtimeClass.getSimpleName

  private val storing = valueSource
    .viaMat(KillSwitches.single)(Keep.right)
    .toMat(valueStore.store)(Keep.left)
    .run()

  def route(keys: Set[Key]): Route = get {
    parameters('from_time.as[Instant], 'until_time.as[Instant] ? Instant.now) { (fromTime, untilTime) =>
      val response = retrieveHistoricalValues(keys, fromTime, untilTime).map(_._2)
      complete(response)
    } ~ optionalHeaderValueByName("Last-Event-ID") {
      case Some(id) =>
        val parseFromTime = Try(Instant.ofEpochMilli(ByteVector.fromBase64(id).get.toLong()).plusNanos(1))
        validate(parseFromTime.isSuccess, "Provided `Last-Event-ID` header's value is not a valid") {
          val fromTime = parseFromTime.get
          val untilTime = Instant.now()
          val historicalValues = retrieveHistoricalValues(keys, fromTime, untilTime)
          val liveValues = receiveLiveValues(keys)
          val response = toServerSentEvents(
            historicalValues.concat(liveValues.buffer(LiveValuesBufferSize, OverflowStrategy.dropNew)))
          complete(response)
        }
      case None =>
        val response = toServerSentEvents(receiveLiveValues(keys))
        complete(response)
    }
  }

  override def shutdown(): Unit = storing.shutdown()

  private def retrieveHistoricalValues(keys: Set[Key],
                                       fromTime: Instant,
                                       untilTime: Instant): Source[(Instant, Value), NotUsed] =
    valueStore
      .retrieveRange(keys, fromTime, untilTime)
      .map { case (_, time, value) => (time, value) }

  private def receiveLiveValues(keys: Set[Key]): Source[(Instant, Value), NotUsed] =
    valueSource
      .filter { case (key, _, _) => keys.contains(key) }
      .map {
        case (_, time, value) => (time, value)
      }

  private def toServerSentEvents(source: Source[(Instant, Value), NotUsed]): Source[ServerSentEvent, NotUsed] =
    source
      .mapAsync(2) {
        case (time, value) =>
          Marshal(value).to[MessageEntity].value.get.get.toStrict(Int.MaxValue.seconds) map { e =>
            val data = e.data
            val id = ByteVector.fromLong(time.toEpochMilli).toBase64
            ServerSentEvent(data = data.utf8String, id = Some(id))
          }
      }
      .keepAlive(15.second, () => ServerSentEvent.heartbeat)
}
