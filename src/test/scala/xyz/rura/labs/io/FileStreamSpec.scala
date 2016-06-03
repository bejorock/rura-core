package xyz.rura.labs.io

import org.scalatest._

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.File

import akka.actor.ActorSystem
import akka.testkit.{ TestActors, DefaultTimeout, ImplicitSender, TestKit }
import akka.actor.Props

import org.apache.commons.io.IOUtils
import org.apache.commons.io.FileUtils

import scala.io.Source
import scala.concurrent.duration._
import scala.concurrent.Await

class FileStreamSpec(_system:ActorSystem) extends TestKit(_system:ActorSystem) with ImplicitSender with WordSpecLike with Matchers with BeforeAndAfterAll
{
	def this() = this(ActorSystem("FileStreamSpec"))

	override def beforeAll = {
		// clean destination directory
		FileUtils.cleanDirectory(new File("tmp/dest/"))
	}

	override def afterAll = {
		TestKit.shutdownActorSystem(system)
		//system.terminate()
	}

	"File Stream" must {
		"read input from {source}" in {
			val factory = FileStreamFactory.src("tmp/src/*.json")

			Await.result(factory.toStream, Duration.Inf).zip(List("1.json", "2.json", "3.json", "4.json")) foreach {
				case (vf, b) => vf.name should === (b)
			}
		}

		"append content" in {
			val factory = FileStreamFactory.src("tmp/src/*.json").pipe{(vf:VirtualFile, callback:(VirtualFile, Exception) => Unit) => 
				callback(VirtualFile(vf.name, vf.path, vf.encoding, IOUtils.toInputStream("{\"name\":\"rana loda lubis\"}")), null)
			}

			Await.result(factory.toStream, Duration.Inf) foreach{vf =>
				IOUtils.toString(vf.inputstream) should === ("{\"name\":\"rana loda lubis\"}")
			}
		}

		"write output to {destination}" in {
			val factory = FileStreamFactory.src("tmp/src/*.json").pipe((vf:VirtualFile, callback:(VirtualFile, Exception) => Unit) => {
				callback(VirtualFile(vf.name, vf.path, vf.encoding, IOUtils.toInputStream("{\"name\":\"rana loda lubis\"}")), null)
			}).pipe(FileStreamFactory.dest("tmp/dest/"))

			Await.result(factory.toStream, Duration.Inf) foreach{vf => true}

			val dstFiles = new File("tmp/dest/").listFiles()

			dstFiles.length should === (4)
		}

		"throw {exception}" in {
			intercept[Exception] {
	  			val factory = FileStreamFactory.src("tmp/src/*.json")

	  			Await.result(factory.toStream, Duration.Inf)

				Await.result(factory.toStream, Duration.Inf)	  			
	  		}
		}
	}
}