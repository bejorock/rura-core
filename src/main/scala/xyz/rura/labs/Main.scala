package xyz.rura.labs

import scala.io.Source
import scala.concurrent.duration._
import scala.concurrent.Await
import scala.collection.immutable.Stream

import org.apache.commons.io.IOUtils

import akka.actor.ActorSystem
import akka.actor.Props
import akka.event.Logging

import kamon.Kamon

import java.text.DecimalFormat

import xyz.rura.labs.io._

object Main
{
	def main(args:Array[String]):Unit = {
		System.setProperty("kamon.enable", "false")

		//Kamon.start()

		implicit val system = ActorSystem("ReactiveStreamSpec")
		implicit val ec = system.dispatcher

		def dummyData = new Iterable[VirtualFile]() {
			def iterator = Iterator.fill(1000){
				val content = scala.util.Random.alphanumeric.take(5).mkString

    			VirtualFile(content, ".", Some(VirtualFile.DEFAULT_ENCODING), IOUtils.toInputStream(content))
			}
		}

		def factorial(number:Int):BigInt = {
			var factValue = BigInt(1)

			for(i <- 2 to number) {
				factValue = factValue * BigInt(i)
			}

			return factValue
		}

		val startTime = java.lang.System.currentTimeMillis()

		// create stream
		val streamFuture = new ReactiveStream(dummyData).pipe((vf:VirtualFile, callback:(VirtualFile, Exception) => Unit) => {
			for(i <- 1 to 5) {
				//factorial(1000)
				callback(VirtualFile(vf.name + "-xoxo", vf.path, vf.encoding, vf.inputstream), null)
			}
		}, 1, "step1").pipe((vf:VirtualFile, callback:(VirtualFile, Exception) => Unit) => {
			for(i <- 1 to 10) {
				//factorial(1000)
				callback(VirtualFile(vf.name + "-xdxd", vf.path, vf.encoding, vf.inputstream), null)
			}
		}, 5, "step2").pipe((vf:VirtualFile, callback:(VirtualFile, Exception) => Unit) => {
			for(i <- 1 to 10) {
				//factorial(1000)
				callback(VirtualFile(vf.name + "-yoyo", vf.path, vf.encoding, vf.inputstream), null)
			}
		}, 10, "step3").pipe((vf:VirtualFile, callback:(VirtualFile, Exception) => Unit) => {
			for(i <- 1 to 10) {
				//factorial(1000)
				callback(VirtualFile(vf.name + "-wkwk", vf.path, vf.encoding, vf.inputstream), null)
			}
		}, 15, "step4").toStream

		val stream = Await.result(streamFuture, Duration.Inf).nonBlocking

		val decimalFormat = new DecimalFormat("#,###,###")
		val format = new DecimalFormat("#,##0.00")
		var counter = 0

		// get result
		stream.result onSuccess{
			case result => result foreach{vf =>
				counter += 1

				val diffTime = java.lang.System.currentTimeMillis() - startTime

				val total = decimalFormat.format(counter)
				val name = vf.name
				val speed = format.format(counter.toDouble / (diffTime / 1000))

				print(s"receive $total messages, last message: $name, speed $speed msg/sec\r")
			}
		}

		var counterError = 0
		// get error
		stream.error onSuccess{
			case error => error foreach{e =>
				counterError += 1

				val diffTime = java.lang.System.currentTimeMillis() - startTime

				val total = decimalFormat.format(counter)
				val name = e.vf.name
				val speed = format.format(counter.toDouble / (diffTime / 1000))

				print(s"error $total messages, last message: $name, speed $speed msg/sec\r")
			}
		}

		stream onComplete{() => 
			system.shutdown()

			//Kamon.shutdown()
		}
	}
}