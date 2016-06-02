package xyz.rura.labs.akka

import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Props
import akka.event.Logging
import akka.actor.ActorLogging
import akka.pattern.ask
import akka.util.Timeout
import akka.actor.TypedActor
import akka.actor.TypedProps

import org.apache.commons.io.IOUtils

import xyz.rura.labs.io._

import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration._
import scala.util.{Success, Failure}
import scala.concurrent.ExecutionContext.Implicits.global

import java.io.ObjectInputStream
import java.io.ByteArrayInputStream

case class WorkerRequest(vf:VirtualFile, mapBytes:Array[Byte])
case class ForwardWorkerRequest(vf:VirtualFile, mapBytes:Array[Byte], caller:ActorRef)
case class WorkerResponse(o:VirtualFile, caller:ActorRef)
case class WorkerException(e:Exception, caller:ActorRef)

class Worker extends Actor with ActorLogging
{
	//log.info("Worker Created!!!")

	def receive = {
		case ForwardWorkerRequest(vf:VirtualFile, mapBytes:Array[Byte], caller:ActorRef) => {
			log.info("Got a new request from: " + sender.path)

			val ref = TypedActor(context).typedActorOf(TypedProps(classOf[AkkaClassLoaderRef], new AkkaClassLoaderRefImpl(sender)))

			val classLoader = new AkkaClassLoader(ref, this.getClass().getClassLoader())
			val in = classLoader.findClass("java.io.ObjectInputStream").getConstructor(classOf[java.io.InputStream]).newInstance(new ByteArrayInputStream(mapBytes)).asInstanceOf[ObjectInputStream]
			val mapper = in.readObject().asInstanceOf[Map]

			mapper.map(vf, (o:VirtualFile, e:Exception) => {
				if(e != null) {
					sender ! WorkerException(e, caller)
				} else {
					sender ! WorkerResponse(o, caller)
				}
			})
		}
	}
}

class WorkerHub(ref:ActorRef) extends Actor with ActorLogging
{
	log.info("Worker Hub Created!!!")

	implicit val timeout = Timeout(5 minutes)

	def receive = {
		case LoadClass(className) => {
			log.info("find class: " + className)

			val classPath = className.replace(".", "/") + ".class"
			val in = this.getClass().getClassLoader().getResourceAsStream(classPath)

			sender ! IOUtils.toByteArray(in)
		}

		case WorkerRequest(vf:VirtualFile, mapBytes:Array[Byte]) => {
			//log.info("got request from: " + sender.path)

			ref ! ForwardWorkerRequest(vf, mapBytes, sender)
		}

		case WorkerResponse(o:VirtualFile, caller:ActorRef) => {
			caller ! o
		}

		case WorkerException(e:Exception, caller:ActorRef) => {
			caller ! e
		}
	}
}

trait WorkerRef
{
	@throws(classOf[Exception])
	def map(vf:VirtualFile, mapBytes:Array[Byte]):Future[VirtualFile]
}

class WorkerRefImpl(ref:ActorRef) extends WorkerRef
{
	implicit val timeout = Timeout(5 minutes)

	private var workerHub:ActorRef = null

	def map(vf:VirtualFile, mapBytes:Array[Byte]):Future[VirtualFile] = {
		if(workerHub == null) {
			workerHub = TypedActor.context.actorOf(Props(classOf[WorkerHub], ref))
		}

		/*val p = Promise[VirtualFile]()

		(workerHub ? WorkerRequest(vf, mapBytes)) onComplete{
			case Success(out) => p success out.asInstanceOf[VirtualFile]
			case Failure(err) => p failure err
		}

		//.mapTo[VirtualFile]

		return p.future*/

		return (workerHub ? WorkerRequest(vf, mapBytes)).mapTo[VirtualFile]
	}
}