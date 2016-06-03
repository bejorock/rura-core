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

class ReactiveStream(iterable:Iterable[VirtualFile])(implicit val system:ActorSystem)
{
	import ReactiveStream.{SetupWorker}
	import system.dispatcher

	private lazy val log = Logging(system, this.getClass) //LoggerFactory.getLogger(classOf[ReactiveStream])
	private lazy val input = iterable.iterator
	private lazy val mapBuffer = ListBuffer[ActorRef]()
	
	private var expired = false
	private var target:Option[ActorSelection] = None
	private var targetPromise:Promise[Option[ActorSelection]] = null

	def this(it:String*)(implicit s:ActorSystem) = this(it.map{i => new VirtualFile(){
		def name:String = i
		def path:String = "temp/" + i
		def encoding:Option[String] = Some(VirtualFile.DEFAULT_ENCODING)
		def inputstream:InputStream = IOUtils.toInputStream(i)
	}})

	def pipe(mapper:Mapper):ReactiveStream = pipe(mapper, 1)

	def pipe(mapper:Mapper, num:Int):ReactiveStream = {
		// create actorref
		val ref = system.actorOf(Props(classOf[CommonReactiveWorker]).withMailbox("prio-mailbox"))

		// append to buffer
		mapBuffer += ref

		return pipe(mapper, system.actorSelection(ref.path), num)
	}

	def pipe(cmap:(VirtualFile, (VirtualFile, Exception) => Unit) => Unit, num:Int):ReactiveStream = pipe(new Mapper() {
		override def map(f:VirtualFile, callback:(VirtualFile, Exception) => Unit):Unit = cmap(f, callback)
	}, num)

	def pipe(cmap:(VirtualFile, (VirtualFile, Exception) => Unit) => Unit):ReactiveStream = pipe(cmap, 1)

	def pipe(mapper:Mapper, worker:ActorSelection, num:Int):ReactiveStream = {
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
			worker ! SetupWorker(mapper, Await.result(targetFuture, Duration.Inf), num)
		}

		return this
	}

	def pipe(mapper:Mapper, worker:ActorSelection):ReactiveStream = pipe(mapper, worker, 1)

	def pipe(cmap:(VirtualFile, (VirtualFile, Exception) => Unit) => Unit, worker:ActorSelection, num:Int):ReactiveStream = pipe(new Mapper() {
		override def map(f:VirtualFile, callback:(VirtualFile, Exception) => Unit):Unit = cmap(f, callback)
	}, worker, num)

	def pipe(cmap:(VirtualFile, (VirtualFile, Exception) => Unit) => Unit, worker:ActorSelection):ReactiveStream = pipe(cmap, worker, 1)

	def isExpired = expired

	def toStream:Future[Iterable[VirtualFile]] = {
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

				return Promise.successful(iterable).future
			}

			case Some(t) => {
				// define client
				val client = system.actorOf(Props(classOf[ReactiveClient], input, target.get).withMailbox("prio-mailbox"))
				val proxy = TypedActor(system).typedActorOf(TypedProps(classOf[ReactiveProxy], new ReactiveProxyImpl(client)).withTimeout(ReactiveStream.defaultTimeout))
				val outputFuture = proxy.output

				outputFuture onSuccess {
					case output => Future { 
						while(!output.hasDefiniteSize) { Thread.sleep(1000) } 

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
	final case class EOF()
	final case class Response(out:VirtualFile)
	final case class Error(err:Throwable)
	final case class Output()
	final case class SetupWorker(mapper:Mapper, nextTarget:Option[ActorSelection], num:Int)
	final case class ResetWorker(eofOrigin:Boolean)
	final case class WorkerNotReady(request:Request)

	implicit def defaultTimeout:Timeout = Timeout(5 minutes)
}