package xyz.rura.labs.io.reactive

import akka.actor.ActorSystem
import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.ActorSelection
import akka.actor.Props
import akka.event.Logging
import akka.actor.ActorLogging
import akka.pattern.ask
import akka.pattern.{pipe => pipeTo}
import akka.pattern.gracefulStop
import akka.util.Timeout
import akka.actor.TypedActor
import akka.actor.TypedProps
import akka.actor.PoisonPill
import akka.actor.Terminated
import akka.routing.RoundRobinPool
import akka.routing.Broadcast
import akka.event.Logging

import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration._
import scala.concurrent.Await
import scala.collection.immutable.{Stream => ScalaStream}
import scala.collection.mutable.ListBuffer
//import scala.concurrent.ExecutionContext.Implicits.global
import scala.reflect.ClassTag
import scala.io.AnsiColor
import scala.language.postfixOps

import org.apache.commons.io.IOUtils

import java.io.InputStream
import java.io.ObjectInputStream
import java.io.ByteArrayInputStream
import java.io.ObjectOutputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.LinkedBlockingQueue

import xyz.rura.labs.io._

class CommonReactiveWorker extends AbstractReactiveWorker
{
	import ReactiveStream.{Request, Response, Error, SetupWorker, ResetWorker, WorkerNotReady, defaultTimeout, EOF}
	import context.dispatcher

	private val processes = ListBuffer[Future[Any]]()

	private var childs = ActorRef.noSender
	private var mapper = Mapper.empty
	private var nextTarget = Option.empty[ActorSelection]

	override def preStart():Unit = log.debug("starting worker {}...", self.path)

	override def postStop():Unit = log.debug("worker {} stopped!!!", self.path)

	override def eof() = {
		// wait for all processes to complete
		Await.result(Future.sequence(processes.toList), Duration.Inf)
	}

	def addProcess(f:Future[Any]):Unit = synchronized {
		// assign to be recent executed process
		processes += f
	}

	def removeProcess(f:Future[Any]):Unit = synchronized {
		// remove future from processes buffer
		processes -= f
	}

	def request(vf:VirtualFile) = {
		// assign current sender
		val session = sender
		val sessionTarget = nextTarget
		// post request to childs
		val reqFuture = (childs ? Request(vf))

		addProcess(reqFuture)

		reqFuture onComplete {
			case _ => removeProcess(reqFuture)
		}

		reqFuture onSuccess {
			case out:VirtualFile => {
				sessionTarget match {
					case None => session ! Response(out)
					case Some(target) => target.tell(Request(out), session)
				}
			}

			case err:Throwable => session ! Error(err)

			case _ => None
		}
	}

	def reset(eofOrigin:Boolean) = {
		// wait to stop all children
		Await.ready(gracefulStop(childs, 5 minutes), 5 minutes)

		if(eofOrigin) {
			// send eof to next target
			nextTarget match {
				case None => sender ! EOF()
				case Some(target) => target forward EOF()
			}
		}

		childs = ActorRef.noSender

		// reset mapper and next target
		mapper = Mapper.empty
		nextTarget = Option.empty[ActorSelection]
	}

	def setup(mapper:Mapper, nextTarget:Option[ActorSelection], num:Int) = {
		// setup childs
		this.childs = this.context.actorOf(RoundRobinPool(num).props(Props(classOf[CommonReactiveWorker.SlaveWorker], mapper)), "slave")

		// setup mapper and next target
		this.mapper = mapper
		this.nextTarget = nextTarget
	}
}

object CommonReactiveWorker
{
	import ReactiveStream.Request

	final class SlaveWorker(mapper:Mapper) extends Actor with ActorLogging 
	{
		override def preStart():Unit = log.debug("starting slave worker {}...", self.path)

		override def postStop():Unit = log.debug("slave worker {} stopped!!!", self.path)

		def receive = {
			case Request(vf) => {
				val session = sender

				try { 
					mapper.map(vf, (out, err) => {
						log.debug("trying to send result to {}", sender.path)

						if(err != null) {
							session ! err
						} else if(out != null) {
							session ! out
						} else {
							session ! None
						}
					})
				} catch {
				  	case e: Exception => session ! e
				}
			}
		}
	}
}