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

import xyz.rura.labs._
import xyz.rura.labs.io._
import xyz.rura.labs.util._

import kamon.trace.Tracer

class CommonReactiveWorker extends AbstractReactiveWorker
{
	import ReactiveStream.{Request, DelegateRequest, Response, Error, SetupWorker, ResetWorker, WorkerNotReady, EOF, defaultTimeout}
	//import context.dispatcher

	//implicit val timeout = Timeout(1 hour)
	private implicit val ec = context.system.dispatchers.lookup("rura.akka.dispatcher.threadpool.simple")

	//private val processes = ListBuffer[Future[Boolean]]()

	private var childs = ActorRef.noSender
	private var nextTarget = Option.empty[ActorSelection]
	private var terminatePromise = Promise[Boolean]()

	// for single worker only
	private var mapper:Option[Mapper] = None

	override def preStart():Unit = log.debug("starting worker {}...", self.path)

	override def postStop():Unit = log.debug("worker {} stopped!!!", self.path)

	override def eof() = {
		
	}

	def request(vf:VirtualFile) = {
		// assign current sender
		val session = sender
		val sessionTarget = nextTarget
		try { 
		  	mapper match {
		  		// post request to childs
		  		case None => childs ! DelegateRequest(vf, session, sessionTarget)

		  		// do the work by itself
		  		case Some(mapperInstance) => {
		  			mapperInstance.map(vf) {
		  				case output:VirtualFile => sessionTarget match {
							case None => session ! Response(output)
							case Some(target) => target.tell(Request(output), session)
						}
						
						case throwable:Throwable => session ! Error(throwable)
		  			}
		  		}
		  	}
		} catch {
			case e: ReactiveException => session ! Error(e)
		  	case e: Exception => session ! Error(new ReactiveException(vf, e))
		}
	}

	def reset(eofOrigin:Boolean) = {
		mapper match {
			case None => {
				childs ! EOF()
				// wait to stop all children
				//Await.ready(gracefulStop(childs, 5 minutes), 5 minutes)
				Await.ready(terminatePromise.future, Duration.Inf)

				childs = ActorRef.noSender
			}

			case Some(mapperInstance) => {
				mapper = None
			}
		}

		if(eofOrigin) {
			// send eof to next target
			nextTarget match {
				case None => sender ! EOF()
				case Some(target) => target forward EOF()
			}
		}

		// reset mapper and next target
		nextTarget = Option.empty[ActorSelection]
		terminatePromise = Promise[Boolean]()
	}

	def setup(mapperProps:ClassProps[_ <: Mapper], nextTarget:Option[ActorSelection], num:Int) = {
		// setup childs router
		if(num > 1) {
			//this.childs = this.context.actorOf(Props(classOf[CommonReactiveWorker.SmallestMailboxRouter], num, mapperProps).withMailbox("prio-mailbox").withDispatcher("worker-pinned-dispatcher"), "slave")
			this.childs = this.context.actorOf(Props(classOf[CommonReactiveWorker.SmallestMailboxRouter], num, mapperProps, terminatePromise).withMailbox("rura.akka.mailbox.bounded-priority").withDispatcher("rura.akka.dispatcher.threadpool.worker"), "slave")
		} else {
			this.mapper = Some(mapperProps.get())
		}

		// setup mapper and next target
		//this.mapper = mapper
		this.nextTarget = nextTarget
	}
}

object CommonReactiveWorker
{
	import ReactiveStream.{Request, DelegateRequest, Response, Error, EOF}

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

				//val promise = Promise[Boolean]()

				//sender ! promise.future

				try { 
					mapper.map(vf){
						case output:VirtualFile => nextTarget match {
							case None => session ! Response(output)
							case Some(target) => target.tell(Request(output), session)
						}

						case throwable:ReactiveException => session ! Error(throwable)
						
						case throwable:Throwable => session ! Error(new ReactiveException(vf, throwable))
					}

					//promise success true
				} catch {
					case e: ReactiveException => session ! Error(e) //promise failure e
				  	case e: Exception => session ! Error(new ReactiveException(vf, e)) //promise failure new ReactiveException(vf, e)
				}
			}

			case EOF() => self ! PoisonPill
		}
	}

	final class SmallestMailboxRouter(num:Int, mapperProps:ClassProps[_ <: Mapper], terminatePromise:Promise[Boolean]) extends Actor with ActorLogging
	{
		private var router = {
			val routees = for(i <- 1 to num) yield {
				//val tmp = this.context.actorOf(Props(classOf[CommonReactiveWorker.SlaveWorker], mapperProps).withMailbox("prio-mailbox").withDispatcher("worker-pinned-dispatcher"), s"$i")
				val tmp = this.context.actorOf(Props(classOf[CommonReactiveWorker.SlaveWorker], mapperProps).withMailbox("rura.akka.mailbox.bounded-priority").withDispatcher("rura.akka.dispatcher.threadpool.worker"), s"$i")
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

					terminatePromise success true
				}
			}

			case EOF() => router.route(Broadcast(EOF()), ActorRef.noSender)

			case otherwise => router.route(otherwise, sender)
		}
	}
}