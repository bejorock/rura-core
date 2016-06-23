package xyz.rura.labs.io

import org.scalatest._

import scala.io.Source
import scala.concurrent.duration._
import scala.concurrent.Await

import org.apache.commons.io.IOUtils

import akka.actor.ActorSystem
import akka.testkit.{ TestActors, DefaultTimeout, ImplicitSender, TestKit }
import akka.actor.Props

import java.io.ByteArrayOutputStream

import kamon.Kamon

class ConsoleStreamSpec(_system:ActorSystem) extends TestKit(_system:ActorSystem) with ImplicitSender with WordSpecLike with Matchers with BeforeAndAfterAll
{
    System.setProperty("kamon.enable", "false")

    def this() = this(ActorSystem("ConsoleStreamSpec"))

    override def beforeAll = {
        Kamon.start()
    }

    override def afterAll = {
        TestKit.shutdownActorSystem(system)

        Kamon.shutdown()
        //system.terminate()
    }

    "Console Stream" must {
        "read input from console" in {
            implicit val inOption = Some(IOUtils.toInputStream("rana"))

            val factory = ConsoleStreamFactory.src(Array("name"))

            Await.result(factory.toStream, Duration.Inf) foreach{vf =>
                IOUtils.toString(vf.inputstream) should === ("rana")
            }
        }

        "write output to console" in {
            implicit val inOption = Some(IOUtils.toInputStream("rana"))

            val out = new ByteArrayOutputStream()
            //implicit val outOption = Some(out)

            val factory = ConsoleStreamFactory.src(Array("name")).pipe(ConsoleStreamFactory.dest(Some(out))) 
            
            //Await.result(factory.toStream, Duration.Inf).size should === (0)

            Await.result(factory.toStream, Duration.Inf) foreach{vf =>
                println(vf.name)
            }

            val buffer = new StringBuffer()
            buffer.append("file: name").append("\n")
            buffer.append("path: .").append("\n")
            buffer.append("encoding: UTF-8").append("\n")
            buffer.append("contents: rana").append("\n")

            //TestKit.awaitCond({out.toByteArray.length > 0}, 1 minute)

            IOUtils.toString(out.toByteArray, "UTF-8") should === (buffer.toString)
        }

        "throw {exception}" in {
            implicit val inOption = Some(IOUtils.toInputStream("rana"))
            
            intercept[Exception] {
                val factory = ConsoleStreamFactory.src(Array("name"))

                Await.result(factory.toStream, Duration.Inf)

                Await.result(factory.toStream, Duration.Inf)                
            }
        }
    }
}