package xyz.rura.labs.io

import org.scalatest._

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.File
import java.text.DecimalFormat

import akka.actor.ActorSystem
import akka.testkit.{ TestActors, DefaultTimeout, ImplicitSender, TestKit }
import akka.actor.Props

import org.apache.commons.io.IOUtils
import org.apache.commons.io.FileUtils

import scala.io.Source
import scala.concurrent.duration._
import scala.concurrent.Await
import scala.concurrent.Future

class FileStreamSpec(_system:ActorSystem) extends TestKit(_system:ActorSystem) with ImplicitSender with WordSpecLike with Matchers with BeforeAndAfterAll
{
	import system.dispatcher

	System.setProperty("kamon.enable", "false")

	def this() = this(ActorSystem("FileStreamSpec"))

	override def beforeAll = {
		// clean destination directory
		FileUtils.cleanDirectory(new File("tmp/dest/"))
	}

	override def afterAll = {
		// reset file

		FileUtils.write(new File("./tmp/src/1.json"), "{}")
		FileUtils.write(new File("./tmp/src/2.json"), "{}")
		FileUtils.write(new File("./tmp/src/3.json"), "{}")
		FileUtils.write(new File("./tmp/src/4.json"), "{}")

		TestKit.shutdownActorSystem(system)
		//system.terminate()
	}

	"File Stream" must {
		"read input from {source}" in {
			val factory = FileStreamFactory.src("tmp/src/*.json")

			val stream = Await.result(factory.toStream, Duration.Inf)

			Await.result(stream.result, Duration.Inf).zip(List("1.json", "2.json", "3.json", "4.json")) foreach {
				case (vf, b) => vf.name should === (b)
			}
		}

		"append content" in {
			val factory = FileStreamFactory.src("tmp/src/*.json").pipe{(vf:VirtualFile, callback:(VirtualFile, Exception) => Unit) => 
				callback(VirtualFile(vf.name, vf.path, vf.encoding, IOUtils.toInputStream("{\"name\":\"rana loda lubis\"}")), null)
			}

			val stream = Await.result(factory.toStream, Duration.Inf) 

			Await.result(stream.result, Duration.Inf) foreach{vf =>
				IOUtils.toString(vf.inputstream) should === ("{\"name\":\"rana loda lubis\"}")
			}
		}

		"watch {files} for a {duration}" in {
			val factory = FileStreamFactory.watch(Array("tmp/src/*.json"), 1 minute)

			val d = (30 seconds)
			// try to change files
			Future {
				val deadline = d.asInstanceOf[FiniteDuration].fromNow

				while(!deadline.isOverdue) {
					FileUtils.write(new File("./tmp/src/1.json"), "{\"name\":\"rana\"}")
					FileUtils.write(new File("./tmp/src/2.json"), "{\"name\":\"rana\"}")
					FileUtils.write(new File("./tmp/src/3.json"), "{\"name\":\"rana\"}")
					FileUtils.write(new File("./tmp/src/4.json"), "{\"name\":\"rana\"}")

					Thread.sleep(5000)
				}
			}

			val decimalFormat = new DecimalFormat("#,###,###")
			var counter = 0
			val stream = Await.result(factory.toStream, d) 

			Await.result(stream.result, Duration.Inf) foreach{vf =>
				counter += 1
				val name = vf.name
				val total = decimalFormat.format(counter)

				print(s"$total changes, last change : $name\r")

				IOUtils.toString(vf.inputstream) should === ("{\"name\":\"rana\"}")
			}
		}

		"write output to {destination}" in {
			val factory = FileStreamFactory.src("tmp/src/*.json").pipe((vf:VirtualFile, callback:(VirtualFile, Exception) => Unit) => {
				callback(VirtualFile(vf.name, vf.path, vf.encoding, IOUtils.toInputStream("{\"name\":\"rana loda lubis\"}")), null)
			}).pipe(FileStreamFactory.dest("tmp/dest/"))

			val stream = Await.result(factory.toStream, Duration.Inf) 

			Await.result(stream.result, Duration.Inf) foreach{vf => true}

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