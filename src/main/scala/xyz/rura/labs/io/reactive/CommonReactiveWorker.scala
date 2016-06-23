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
//import akka.util.Reflect
import akka.actor.TypedActor
import akka.actor.TypedProps
import akka.actor.PoisonPill
import akka.actor.Terminated
import akka.routing.RoundRobinPool
import akka.routing.RoundRobinGroup
import akka.routing.BalancingPool
import akka.routing.SmallestMailboxPool
import akka.routing.Broadcast
import akka.event.Logging
import akka.routing.{ ActorRefRoutee, SmallestMailboxRoutingLogic, RoundRobinRoutingLogic, Router }

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
import scala.util.{Success, Failure}

import org.apache.commons.io.IOUtils

import java.io.InputStream
import java.io.ObjectInputStream
import java.io.ByteArrayInputStream
import java.io.ObjectOutputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.LinkedBlockingQueue

import xyz.rura.labs.io._
import xyz.rura.labs.util._

import kamon.trace.Tracer

class CommonReactiveWorker extends AbstractReactiveWorker
{
	import ReactiveStream.{Request, DelegateRequest, Response, Error, SetupWorker, ResetWorker, WorkerNotReady, EOF, defaultTimeout}
	import context.dispatcher

	//implicit val timeout = Timeout(1 hour)

	private val processes = ListBuffer[Future[Boolean]]()

	private var childs = ActorRef.noSender
	//private var mapper = Mapper.empty
	private var nextTarget = Option.empty[ActorSelection]

	override def preStart():Unit = log.debug("starting worker {}...", self.path)

	override def postStop():Unit = log.debug("worker {} stopped!!!", self.path)

	override def eof() = {
		log.debug("trying to stop {} slave process", processes.size)

		if(processes.size > 0) {
			// wait for all processes to complete
			Await.ready(Future.sequence(processes.toList), Duration.Inf)
		}

		log.debug("all child has done processing messages")
	}

	def addProcess(f:Future[Boolean]):Unit = synchronized {
		// assign to be recent executed process
		processes += f
	}

	def removeProcess(f:Future[Boolean]):Unit = synchronized {
		// remove future from processes buffer
		processes -= f
	}

	def request(vf:VirtualFile) = {
		//log.debug(Tracer.currentContext.name + "_" + Tracer.currentContext.token)

		// assign current sender
		val session = sender
		val sessionTarget = nextTarget
		// post request to childs
		try { 
		  	val reqFuture:Future[Boolean] = Await.result((childs ? DelegateRequest(vf, session, sessionTarget)).mapTo[Future[Boolean]], Duration.Inf)

			addProcess(reqFuture)

			reqFuture onComplete {
				case Success(data) => removeProcess(reqFuture)
				case Failure(err) => {
					session ! Error(err)

					removeProcess(reqFuture)
				}
			}
		} catch {
		  	case e: Exception => e.printStackTrace()
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
		//mapper = Mapper.empty
		nextTarget = Option.empty[ActorSelection]
	}

	def setup(mapperProps:ClassProps[_ <: Mapper], nextTarget:Option[ActorSelection], num:Int) = {
		// setup childs router
		//this.childs = this.context.actorOf(BalancingPool(num).props(Props(classOf[CommonReactiveWorker.SlaveWorker], mapper).withMailbox("prio-mailbox").withDispatcher("worker-pinned-dispatcher")), "slave")

		this.childs = this.context.actorOf(Props(classOf[CommonReactiveWorker.SmallestMailboxRouter], num, mapperProps).withMailbox("prio-mailbox").withDispatcher("worker-pinned-dispatcher"), "slave")

		// setup mapper and next target
		//this.mapper = mapper
		this.nextTarget = nextTarget
	}
}

object CommonReactiveWorker
{
	import ReactiveStream.{Request, DelegateRequest, Response, Error}

	final class SlaveWorker(mapperProps:ClassProps[_ <: Mapper]) extends Actor with ActorLogging 
	{
		private val mapper:Mapper = mapperProps.get()

		override def preStart():Unit = {
			mapper.onStart()

			log.debug("starting slave worker {}...", self.path)
		}

		override def postStop():Unit = {
			mapper.onStop()

			log.debug("slave worker {} stopped!!!", self.path)
		}

		def receive = {
			case DelegateRequest(vf, session, nextTarget) => {
				//log.debug("got message {}", vf.name)

				val promise = Promise[Boolean]()

				sender ! promise.future

				try { 
					mapper.map(vf, (out, err) => {
						if(err != null) {
							session ! Error(err)
						} else if(out != null) {
							nextTarget match {
								case None => session ! Response(out)
								case Some(target) => target.tell(Request(out), session)
							}
						} else {
							//session ! None
						}
					})

					promise success true
				} catch {
				  	case e: Exception => promise failure e
				}
			}
		}
	}

	final class SmallestMailboxRouter(num:Int, mapperProps:ClassProps[_ <: Mapper]) extends Actor with ActorLogging
	{
		private var router = {
			val routees = for(i <- 1 to num) yield {
				val tmp = this.context.actorOf(Props(classOf[CommonReactiveWorker.SlaveWorker], mapperProps).withMailbox("prio-mailbox").withDispatcher("worker-pinned-dispatcher"), s"$i")
				// watch routee lifecycle
				this.context watch tmp

				ActorRefRoutee(tmp)
			}

			Router(SmallestMailboxRoutingLogic(), routees)
		}

		override def preStart():Unit = log.debug("starting slave router {}...", self.path)

		override def postStop():Unit = log.debug("slave router {} stopped!!!", self.path)

		def receive = {
			case Terminated(route) => {
				router = router.removeRoutee(route)

				// stop itself where there are no routees
				if(router.routees.size == 0) {
					self ! PoisonPill
				}
			}

			case otherwise => router.route(otherwise, sender)
		}
	}
}