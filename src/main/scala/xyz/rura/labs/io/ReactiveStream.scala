package xyz.rura.labs.io

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

import xyz.rura.labs.io.reactive._
import xyz.rura.labs.util._

import kamon.util.RelativeNanoTimestamp

class ReactiveStream(iterable:Iterable[VirtualFile], streamName:String = ReactiveStream.defaultName)(implicit val system:ActorSystem)
{
	import ReactiveStream.{SetupWorker}
	//import system.dispatcher

	private implicit val ec = system.dispatchers.lookup("rura.akka.dispatcher.threadpool.simple")

	private lazy val log = Logging(system, this.getClass) //LoggerFactory.getLogger(classOf[ReactiveStream])
	private lazy val input = iterable.iterator
	private lazy val mapBuffer = ListBuffer[ActorRef]()
	
	private var mapCounter = 0
	private var expired = false
	private var target:Option[ActorSelection] = None
	private var targetPromise:Promise[Option[ActorSelection]] = null

	def pipe(mapperProps:ClassProps[_ <: Mapper]):ReactiveStream = pipe(mapperProps, 1)

	def pipe(mapperProps:ClassProps[_ <: Mapper], name:String):ReactiveStream = pipe(mapperProps, 1, name)

	def pipe(mapperProps:ClassProps[_ <: Mapper], num:Int):ReactiveStream = pipe(mapperProps, num, None)

	def pipe(mapperProps:ClassProps[_ <: Mapper], num:Int, name:String):ReactiveStream = pipe(mapperProps, num, Some(name))

	def pipe(mapperProps:ClassProps[_ <: Mapper], num:Int, name:Option[String]):ReactiveStream = {
		// create actorref
		val ref = name match {
			case None => {
				mapCounter += 1

				system.actorOf(Props(classOf[CommonReactiveWorker]).withMailbox("rura.akka.mailbox.bounded-priority").withDispatcher("rura.akka.dispatcher.forkjoin.mainactor"), s"$streamName-map$mapCounter")
			}
			case Some(n) => system.actorOf(Props(classOf[CommonReactiveWorker]).withDispatcher("rura.akka.dispatcher.forkjoin.mainactor").withMailbox("rura.akka.mailbox.bounded-priority"), n)
		}

		// append to buffer
		mapBuffer += ref

		return pipe(mapperProps, system.actorSelection(ref.path), num)
	}

	//def pipe(cmap:(VirtualFile, (VirtualFile, Exception) => Unit) => Unit, num:Int)

	def pipe(num:Int)(cmap:PartialFunction[(VirtualFile, MapperOutput), Unit]):ReactiveStream = pipe(ClassProps[Mapper](new AbstractMapper() {
		//override def map(f:VirtualFile, callback:(VirtualFile, Exception) => Unit):Unit = cmap(f, callback)
		def map(f:VirtualFile, output:MapperOutput):Unit = cmap(f -> output)
	}), num)

	def pipe(num:Int, name:String)(cmap:PartialFunction[(VirtualFile, MapperOutput), Unit]):ReactiveStream = pipe(ClassProps[Mapper](new AbstractMapper() {
		def map(f:VirtualFile, output:MapperOutput):Unit = cmap(f -> output)
	}), num, name)

	def pipe(name:String)(cmap:PartialFunction[(VirtualFile, MapperOutput), Unit]):ReactiveStream = pipe(1, name)(cmap)

	def pipe()(cmap:PartialFunction[(VirtualFile, MapperOutput), Unit]):ReactiveStream = pipe(1)(cmap)

	def pipe(mapperProps:ClassProps[_ <: Mapper], worker:ActorSelection, num:Int):ReactiveStream = {
		if(expired) {
			throw new Exception("this builder has expired")
		}

		if(targetPromise == null) {
			target = Some(worker)
			targetPromise = Promise[Option[ActorSelection]]()
		} else {
			targetPromise success Some(worker)
			targetPromise = Promise[Option[ActorSelection]]()
		}

		val targetFuture = targetPromise.future
		Future {
			// setup worker
			worker ! SetupWorker(mapperProps, Await.result(targetFuture, Duration.Inf), num)
		}

		return this
	}

	def pipe(mapperProps:ClassProps[_ <: Mapper], worker:ActorSelection):ReactiveStream = pipe(mapperProps, worker, 1)

	def pipe(worker:ActorSelection, num:Int)(cmap:PartialFunction[(VirtualFile, MapperOutput), Unit]):ReactiveStream = pipe(ClassProps[Mapper](new AbstractMapper() {
		def map(f:VirtualFile, output:MapperOutput):Unit = cmap(f -> output)
	}), worker, num)

	def pipe(worker:ActorSelection)(cmap:PartialFunction[(VirtualFile, MapperOutput), Unit]):ReactiveStream = pipe(worker, 1)(cmap)

	def isExpired = expired

	def toStream:Future[ReactiveOutput] = {
		if(expired) {
			throw new Exception("this builder has expired")
		}

		// set this builder to expired
		expired = true

		if(targetPromise != null) {
			targetPromise success None
		}

		target match {
			case None => {
				//log.error("target cannot null", new Exception("not found valid target"))

				return Promise.successful(new DirectReactiveOutput(iterable)).future
			}

			case Some(t) => {
				// define client
				val client = system.actorOf(Props(classOf[ReactiveClient], input, target.get).withMailbox("rura.akka.mailbox.bounded-priority").withDispatcher("rura.akka.dispatcher.forkjoin.mainactor"), s"$streamName-client")
				val proxy = TypedActor(system).typedActorOf(TypedProps(classOf[ReactiveProxy], new ReactiveProxyImpl(client)).withTimeout(ReactiveStream.defaultTimeout).withDispatcher("rura.akka.dispatcher.forkjoin.mainactor"), s"$streamName-proxy")
				val outputFuture = proxy.output

				outputFuture onSuccess {
					case output => output onComplete{
						// stop client
						client ! PoisonPill.getInstance
						// stop proxy
						TypedActor(system).poisonPill(proxy)
						// stop workers created by this builder
						mapBuffer foreach{mapper => mapper ! PoisonPill.getInstance}
					}
				}

				return outputFuture
			}
		}
	}
}

object ReactiveStream
{
	final case class Request(vf:VirtualFile)
	final case class DelegateRequest(vf:VirtualFile, session:ActorRef, nextTarget:Option[ActorSelection])
	final case class EOF()
	final case class Response(out:VirtualFile)
	final case class Error(err:Throwable)
	final case class Output()
	final case class SetupWorker(mapperProps:ClassProps[_ <: Mapper], nextTarget:Option[ActorSelection], num:Int)
	final case class ResetWorker(eofOrigin:Boolean)
	final case class WorkerNotReady(request:Request)
	final case class Ping()
	final case class Pong()

	implicit def defaultTimeout:Timeout = Timeout(5 minutes)

	private var counter = 0
	
	def defaultName:String = {
		counter += 1

		return s"reactivestream$counter"
	}

	def kamonEnabled:Boolean = {
		// default enable kamon
		if(System.getProperty("kamon.enable") == null) {
			return true
		} else {
			return System.getProperty("kamon.enable").toBoolean
		}
	}
}