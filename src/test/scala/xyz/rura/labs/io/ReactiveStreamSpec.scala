package xyz.rura.labs.io

import org.scalatest._

import scala.io.Source
import scala.concurrent.duration._
import scala.concurrent.Await
import scala.collection.immutable.Stream

import org.apache.commons.io.IOUtils

import akka.actor.ActorSystem
import akka.testkit.{ TestActors, DefaultTimeout, ImplicitSender, TestKit }
import akka.actor.Props
import akka.event.Logging

import kamon.Kamon

import java.text.DecimalFormat
//import java.util.concurrent.LinkedBlockingQueue

class ReactiveStreamSpec(_system:ActorSystem) extends TestKit(_system:ActorSystem) with ImplicitSender with WordSpecLike with Matchers with BeforeAndAfterAll 
{
	System.setProperty("kamon.enable", "false")

	//private val log = Logging(system, this.getClass)

	def this() = this(ActorSystem("ReactiveStreamSpec"))

    override def beforeAll = {
    	//Kamon.start()

    	//val someHistogram = Kamon.metrics.histogram("some-histogram")
		//val someCounter = Kamon.metrics.counter("some-counter")

		//someHistogram.record(42)
		//someCounter.increment()
    }

    override def afterAll = {
    	//Kamon.shutdown()

        TestKit.shutdownActorSystem(system)
        //system.terminate()
    }

    "Reactive Stream" must {
    	"do heavy computation" in {
    		// generate dummy data
    		def dummyData = new Iterable[VirtualFile]() {
    			def iterator = Iterator.fill(100){
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
			val streamFuture = new ReactiveStream(dummyData).pipe("step1"){
				case (vf, output) => {
					//factorial(1000)
					for(i <- 1 to 5) {
						//factorial(1000)
						output.collect(VirtualFile(vf.name + "-xoxo", vf.path, vf.encoding, vf.inputstream))
					}

					//Thread.sleep(100)
				}
			}.pipe(5, "step2"){
				case (vf, output) => {
					//factorial(2000)
					for(i <- 1 to 10) {
						//factorial(1000)
						output.collect(VirtualFile(vf.name + "-xdxd", vf.path, vf.encoding, vf.inputstream))
					}

					Thread.sleep(10)
				}
			}.pipe(10, "step3"){
				case (vf, output) => {
					//factorial(3000)
					for(i <- 1 to 10) {
						//factorial(1000)
						output.collect(VirtualFile(vf.name + "-yoyo", vf.path, vf.encoding, vf.inputstream))
					}

					Thread.sleep(50)
				}
			}.pipe(15, "step4"){
				case (vf, output) => {
					//factorial(4000)
					for(i <- 1 to 10) {
						//factorial(1000)
						output.collect(VirtualFile(vf.name + "-wkwk", vf.path, vf.encoding, vf.inputstream))
					}

					Thread.sleep(100)
				}
			}.toStream

			val stream = Await.result(streamFuture, Duration.Inf).nonBlocking

			val decimalFormat = new DecimalFormat("#,###,###")
			val format = new DecimalFormat("#,##0.00")
			var counter = 0
			Await.result(stream.result, Duration.Inf) foreach{vf =>
				counter += 1

				val diffTime = java.lang.System.currentTimeMillis() - startTime

				val total = decimalFormat.format(counter)
				val name = vf.name
				val speed = format.format(counter.toDouble / (diffTime / 1000))

				print(s"receive $total messages, last message: $name, speed $speed msg/sec\r")

				//vf.name.endsWith("-xoxo-xdxd-yoyo-wkwk") should === (true)
			}
    	}
    }
}