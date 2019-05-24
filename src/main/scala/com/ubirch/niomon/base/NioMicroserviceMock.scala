package com.ubirch.niomon.base

import akka.Done
import akka.kafka.scaladsl.Consumer.{DrainingControl, NoopControl}
import com.typesafe.config.{Config, ConfigFactory}
import com.ubirch.niomon.util.{KafkaPayload, KafkaPayloadFactory}
import net.manub.embeddedkafka.NioMockKafka
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.{Deserializer, Serializer, StringDeserializer, StringSerializer}
import org.mockito.MockitoSugar
import org.mockito.stubbing.ReturnsDeepStubs
import org.redisson.api.RedissonClient

import scala.concurrent.Future
import scala.util.Try

class NioMicroserviceMock[I, O](logicFactory: NioMicroservice[I, O] => NioMicroserviceLogic[I, O])(implicit
  inputPayloadFactory: KafkaPayloadFactory[I],
  outputPayloadFactory: KafkaPayloadFactory[O]
) extends NioMicroservice[I, O] {
  var outputTopics: Map[String, String] = Map()
  var config: Config = ConfigFactory.empty()
  var redisson: RedissonClient = {
    val mock = MockitoSugar.mock[RedissonClient](ReturnsDeepStubs)
    mock
  }

  override def context: NioMicroservice.Context = new NioMicroservice.Context(redisson, config)

  override lazy val onlyOutputTopic: String = {
    if (outputTopics.size != 1)
      throw new IllegalStateException("you cannot use `onlyOutputTopic` with multiple output topics defined!")
    outputTopics.values.head
  }

  lazy val inputPayload: KafkaPayload[I] = inputPayloadFactory(context)
  lazy val outputPayload: KafkaPayload[O] = outputPayloadFactory(context)
  lazy val logic: NioMicroserviceLogic[I, O] = logicFactory(this)

  def run: DrainingControl[Done] = DrainingControl((NoopControl, Future.successful(Done)))

  var errors: Vector[Throwable] = Vector()
  var results: Vector[ProducerRecord[String, O]] = Vector()

  val kafkaMocks: NioMockKafka = new NioMockKafka {
    override def put[K, T](kSer: Serializer[K], vSer: Serializer[T], record: ProducerRecord[K, T]): Unit = {
      val serializedKey = kSer.serialize(record.topic(), record.key())
      val serializedValue = vSer.serialize(record.topic(), record.value())

      val key = new StringDeserializer().deserialize(record.topic(), serializedKey)
      val value = inputPayload.deserializer.deserialize(record.topic(), record.headers(), serializedValue)

      val processed = Try(logic.processRecord(new ConsumerRecord[String, I](record.topic(), record.partition(), 0, 0,
        null, 0L, 0, 0, key, value, record.headers())))

      processed.fold({
        errors :+= _
      }, {
        results :+= _
      })
    }

    override def get[K, T](topics: Set[String], num: Int, keyDeserializer: Deserializer[K],
      valueDeserializer: Deserializer[T]): Map[String, List[(K, T)]] = {
      var matched = Vector[ProducerRecord[String, O]]()
      var nonMatched = Vector[ProducerRecord[String, O]]()
      var i = 0

      results.foreach { r =>
        if (i < num && topics.contains(r.topic())) {
          matched :+= r
          i += 1
        } else {
          nonMatched :+= r
        }
      }
      results = nonMatched

      matched
        .map { pr =>
          val serializedValue = outputPayload.serializer.serialize(pr.topic(), pr.headers(), pr.value())
          val deserializedValue = valueDeserializer.deserialize(pr.topic(), pr.headers(), serializedValue)

          val serializedKey = new StringSerializer().serialize(pr.topic(), pr.headers(), pr.key())
          val deserializedKey = keyDeserializer.deserialize(pr.topic(), pr.headers(), serializedKey)
          (pr.topic(), deserializedKey, deserializedValue)
        }
        .groupBy(_._1)
        .map { case (key, v) => key -> v.map { x => x._2 -> x._3 }.toList }
    }
  }
}

object NioMicroserviceMock {
  def apply[I, O](logicFactory: NioMicroservice[I, O] => NioMicroserviceLogic[I, O])(implicit
    inputPayloadFactory: KafkaPayloadFactory[I],
    outputPayloadFactory: KafkaPayloadFactory[O]
  ): NioMicroserviceMock[I, O] = new NioMicroserviceMock(logicFactory)
}