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

import java.text.DecimalFormat
//import java.util.concurrent.LinkedBlockingQueue

class ReactiveStreamSpec(_system:ActorSystem) extends TestKit(_system:ActorSystem) with ImplicitSender with WordSpecLike with Matchers with BeforeAndAfterAll 
{
	//private val log = Logging(system, this.getClass)

	def this() = this(ActorSystem("ReactiveStreamSpec"))

    override def beforeAll = {

    }

    override def afterAll = {
        TestKit.shutdownActorSystem(system)
        //system.terminate()
    }

    "Reactive Stream" must {
    	"do heavy computation" in {
    		// generate dummy data
    		//val dummyData = for(i <- 0 to 1000000) yield scala.util.Random.alphanumeric.take(5).mkString
    		def dummyData = new Iterable[VirtualFile]() {
    			def iterator = Iterator.fill(500000){
    				val content = scala.util.Random.alphanumeric.take(5).mkString

	    			VirtualFile(content, ".", Some(VirtualFile.DEFAULT_ENCODING), IOUtils.toInputStream(content))
    			}
    		}

    		// create stream
			val streamFuture = new ReactiveStream(dummyData).pipe((vf:VirtualFile, callback:(VirtualFile, Exception) => Unit) => {
				callback(VirtualFile(vf.name + "-xoxo", vf.path, vf.encoding, vf.inputstream), null)
			}, 10).pipe{(vf:VirtualFile, callback:(VirtualFile, Exception) => Unit) =>
				callback(VirtualFile(vf.name + "-xdxd", vf.path, vf.encoding, vf.inputstream), null)
			}.pipe((vf:VirtualFile, callback:(VirtualFile, Exception) => Unit) => {
				callback(VirtualFile(vf.name + "-yoyo", vf.path, vf.encoding, vf.inputstream), null)
			}, 20).pipe{(vf:VirtualFile, callback:(VirtualFile, Exception) => Unit) =>
				callback(VirtualFile(vf.name + "-wkwk", vf.path, vf.encoding, vf.inputstream), null)
			}.toStream

			val stream = Await.result(streamFuture, Duration.Inf)

			/*stream.zip(dummyData) foreach{
				case(vf, d) => IOUtils.toString(vf.inputstream).equals(d + "-xoxo-xdxd-yoyo-wkwk")
			}*/

			val decimalFormat = new DecimalFormat("#,###,###")
			var counter = 0
			stream foreach{vf =>
				counter += 1

				val total = decimalFormat.format(counter)
				val name = vf.name

				print(s"receive $total messages, last message: $name\r")

				vf.name.endsWith("-xoxo-xdxd-yoyo-wkwk") should === (true)
			}
    	}

    	/*"do infinite computation" in {
    		def iterable = new Iterable[VirtualFile]() {
    			def iterator = Iterator.continually({
	    			val content = scala.util.Random.alphanumeric.take(5).mkString

	    			VirtualFile(content, ".", Some(VirtualFile.DEFAULT_ENCODING), IOUtils.toInputStream(content))
	    		})
    		}

    		// create stream
			val streamFuture = new ReactiveStream(iterable).pipe((vf:VirtualFile, callback:(VirtualFile, Exception) => Unit) => {
				callback(VirtualFile(vf.name + "-xoxo", vf.path, vf.encoding, vf.inputstream), null)
			}, 10).pipe{(vf:VirtualFile, callback:(VirtualFile, Exception) => Unit) =>
				callback(VirtualFile(vf.name + "-xdxd", vf.path, vf.encoding, vf.inputstream), null)
			}.pipe((vf:VirtualFile, callback:(VirtualFile, Exception) => Unit) => {
				callback(VirtualFile(vf.name + "-yoyo", vf.path, vf.encoding, vf.inputstream), null)
			}, 20).pipe{(vf:VirtualFile, callback:(VirtualFile, Exception) => Unit) =>
				callback(VirtualFile(vf.name + "-wkwk", vf.path, vf.encoding, vf.inputstream), null)
			}.toStream

			val stream = Await.result(streamFuture, Duration.Inf)

			val decimalFormat = new DecimalFormat("#,###,###")
			var counter = 0l
			stream foreach{vf =>
				//val content = IOUtils.toString(vf.inputstream)

				counter += 1
				val total = decimalFormat.format(counter)
				val name = vf.name

				print(s"receive $total messages, last message: $name\r")

				vf.name.endsWith("-xoxo-xdxd-yoyo-wkwk") should === (true)
			}
    	}*/
    }
}