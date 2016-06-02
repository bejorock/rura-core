package xyz.rura.labs.io

import akka.actor.ActorSystem
import akka.actor.Actor
import akka.actor.ActorRef
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

import org.apache.commons.io.IOUtils

import java.io.InputStream
import java.io.ObjectInputStream
import java.io.ByteArrayInputStream
import java.io.ObjectOutputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.LinkedBlockingQueue

import org.slf4j.LoggerFactory

abstract class AbstractReactiveWorker extends Actor with ActorLogging
{
	import ReactiveStream.{Request, Response, Error, SetupWorker, ResetWorker, WorkerNotReady, defaultTimeout}
	import context.dispatcher

	def eof():Unit = {}

	def request(vf:VirtualFile):Unit

	def reset(eofOption:Option[VirtualFile.EOF]):Unit

	def setup(mapper:Map, nextTarget:Option[ActorRef], num:Int):Unit

	def notReady(vf:VirtualFile):Unit = {}

	final def receive = synchronized {
		case SetupWorker(mapper, nextTarget, num) => {
			// do worker initial setup
			setup(mapper, nextTarget, num)

			// actor becomes active
			context.become(active)
		}

		case Request(vf) =>{
			// send worker not ready status to sender
			sender ! WorkerNotReady(Request(vf))

			notReady(vf)
		}
	}

	final def active:Receive = synchronized {
		case Request(vf:VirtualFile.EOF) => {
			eof()

			self.tell(ResetWorker(Some(vf)), sender)
		}

		case Request(vf) => request(vf)

		case ResetWorker(eofOption) => {
			reset(eofOption)

			// actor becomes inactive
			context.unbecome()
		}
	}
}

class CommonReactiveWorker extends AbstractReactiveWorker
{
	import ReactiveStream.{Request, Response, Error, SetupWorker, ResetWorker, WorkerNotReady, defaultTimeout}
	import context.dispatcher

	private val processes = ListBuffer[Future[Any]]()

	private var childs = ActorRef.noSender
	private var mapper = Map.empty
	private var nextTarget = Option.empty[ActorRef]

	override def preStart():Unit = log.debug("starting worker {}...", self.path)

	override def postStop():Unit = log.debug("worker {} stopped!!!", self.path)

	override def eof() = {
		// wait for all processes to complete
		Await.result(Future.sequence(processes.toList), Duration.Inf)
	}

	def request(vf:VirtualFile) = {
		// assign current sender
		val session = sender
		val sessionTarget = nextTarget
		// post request to childs
		val reqFuture = (childs ? Request(vf))

		reqFuture onComplete {
			// remove future from processes buffer
			case _ => processes -= reqFuture
		}

		// assign to be recent executed process
		processes += reqFuture

		reqFuture onSuccess {
			case out:VirtualFile => {
				sessionTarget match {
					case None => session ! Response(out)
					case Some(target) => target.tell(Request(out), session)
				}
			}

			case err:Throwable => session ! Error(err)
		}
	}

	def reset(eofOption:Option[VirtualFile.EOF]) = {
		// wait to stop all children
		Await.ready(gracefulStop(childs, 5 minutes), 5 minutes)

		eofOption match {
			case None => {}
			case Some(eof) => {
				// send eof to next target
				nextTarget match {
					case None => sender ! Response(eof)
					case Some(target) => target forward Request(eof)
				}
			}
		}

		childs = ActorRef.noSender

		// reset mapper and next target
		mapper = Map.empty
		nextTarget = Option.empty[ActorRef]
	}

	def setup(mapper:Map, nextTarget:Option[ActorRef], num:Int) = {
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

	final class SlaveWorker(mapper:Map) extends Actor with ActorLogging 
	{
		override def preStart():Unit = log.debug("starting slave worker {}...", self.path)

		override def postStop():Unit = log.debug("slave worker {} stopped!!!", self.path)

		def receive = {
			case Request(vf) => {
				mapper.map(vf, (out, err) => {
					if(err != null) {
						sender ! err
					} else {
						sender ! out
					}
				})
			}
		}
	}
}

class ReactiveClient(input:Iterator[VirtualFile], target:ActorRef) extends Actor with ActorLogging
{
	import ReactiveStream.{Request, Response, Error, Output, WorkerNotReady}
	import context.dispatcher

	Future {
		// start sending input
		input foreach{vf => target ! Request(vf)}
		// send eof broadcast
		target ! Request(VirtualFile.EOF())
	}

	private var queue = new LinkedBlockingQueue[VirtualFile]()

	private def output = Future {
		def next(vf:VirtualFile):ScalaStream[VirtualFile] = {
			if(vf.isInstanceOf[VirtualFile.EOF]) {
				Stream.empty
			} else {
				vf #:: next(queue.take())
			}
		}

		next(queue.take())
	}

	override def preStart():Unit = log.debug("starting client {}...", self.path)

	override def postStop():Unit = log.debug("client {} stopped!!!", self.path)

	def receive = {
		case Response(out) => {
			// add to queue
			queue.put(out)
		}

		case WorkerNotReady(request) => {
			// mark session sender
			val session = sender
			
			Future {
				Thread.sleep(1000)
				// resend the request
				session ! request
			}
		}

		case Error(err) => log.error(err, "cannot process message at {}", sender.path)

		case Output() => { 
			output pipeTo sender
		}
	}
}

trait ReactiveProxy 
{
	def output:Future[ScalaStream[VirtualFile]]
}

class ReactiveProxyImpl(client:ActorRef) extends ReactiveProxy with TypedActor.PostStop
{
	import ReactiveStream.{Output, defaultTimeout}

	private lazy val log = Logging(TypedActor.context.system, TypedActor.context.self)

	def postStop():Unit = log.debug("proxy {} stopped!!!", TypedActor.context.self.path)

	override def output:Future[ScalaStream[VirtualFile]] = (client ? Output()).mapTo[ScalaStream[VirtualFile]]
}

class ReactiveStream(iterable:Iterable[VirtualFile])(implicit val system:ActorSystem)
{
	import ReactiveStream.{SetupWorker}
	import system.dispatcher

	private lazy val log = Logging(system, this.getClass) //LoggerFactory.getLogger(classOf[ReactiveStream])
	private lazy val input = iterable.iterator
	private lazy val mapBuffer = ListBuffer[ActorRef]()
	
	private var expired = false
	private var target:Option[ActorRef] = None
	private var targetPromise:Promise[Option[ActorRef]] = null

	def this(it:String*)(implicit s:ActorSystem) = this(it.map{i => new VirtualFile(){
		def name:String = i
		def path:String = "temp/" + i
		def encoding:Option[String] = Some(VirtualFile.DEFAULT_ENCODING)
		def inputstream:InputStream = IOUtils.toInputStream(i)
	}})

	def pipe(mapper:Map):ReactiveStream = pipe(mapper, 1)

	def pipe(mapper:Map, num:Int):ReactiveStream = {
		// create actorref
		val ref = system.actorOf(Props(classOf[CommonReactiveWorker]))

		// append to buffer
		mapBuffer += ref

		return pipe(mapper, ref, num)
	}

	def pipe(cmap:(VirtualFile, (VirtualFile, Exception) => Unit) => Unit, num:Int):ReactiveStream = pipe(new Map() {
		override def map(f:VirtualFile, callback:(VirtualFile, Exception) => Unit):Unit = cmap(f, callback)
	}, num)

	def pipe(cmap:(VirtualFile, (VirtualFile, Exception) => Unit) => Unit):ReactiveStream = pipe(cmap, 1)

	def pipe(mapper:Map, worker:ActorRef, num:Int):ReactiveStream = {
		if(expired) {
			throw new Exception("this builder has expired")
		}

		if(targetPromise == null) {
			target = Some(worker)
			targetPromise = Promise[Option[ActorRef]]()
		} else {
			targetPromise success Some(worker)
			targetPromise = Promise[Option[ActorRef]]()
		}

		val targetFuture = targetPromise.future
		Future {
			// setup worker
			worker ! SetupWorker(mapper, Await.result(targetFuture, Duration.Inf), num)
		}

		return this
	}

	def pipe(mapper:Map, worker:ActorRef):ReactiveStream = pipe(mapper, worker, 1)

	def pipe(cmap:(VirtualFile, (VirtualFile, Exception) => Unit) => Unit, worker:ActorRef, num:Int):ReactiveStream = pipe(new Map() {
		override def map(f:VirtualFile, callback:(VirtualFile, Exception) => Unit):Unit = cmap(f, callback)
	}, worker, num)

	def pipe(cmap:(VirtualFile, (VirtualFile, Exception) => Unit) => Unit, worker:ActorRef):ReactiveStream = pipe(cmap, worker, 1)

	def isExpired = expired

	def toStream:Future[ScalaStream[VirtualFile]] = {
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

				return Promise.successful(input.toStream).future
			}

			case Some(t) => {
				// define client
				val client = system.actorOf(Props(classOf[ReactiveClient], input, target.get))
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

object ReactiveStream extends App
{
	final case class Request(vf:VirtualFile)
	final case class Response(out:VirtualFile)
	final case class Error(err:Throwable)
	final case class Output()
	final case class SetupWorker(mapper:Map, nextTarget:Option[ActorRef], num:Int)
	final case class ResetWorker(eofOption:Option[VirtualFile.EOF])
	final case class WorkerNotReady(request:Request)

	implicit val system = ActorSystem("ReactiveStream")

	implicit def defaultTimeout:Timeout = Timeout(5 minutes)

	import system.dispatcher

	val dummyData = for(i <- 0 to 1000000) yield scala.util.Random.alphanumeric.take(5).mkString
	val streamFuture = new ReactiveStream(dummyData.toList : _*).pipe((vf:VirtualFile, callback:(VirtualFile, Exception) => Unit) => {
		callback(VirtualFile(vf.name + "-xoxo", vf.path, vf.encoding, vf.inputstream), null)
	}, 10).pipe{(vf:VirtualFile, callback:(VirtualFile, Exception) => Unit) =>
		callback(VirtualFile(vf.name + "-xdxd", vf.path, vf.encoding, vf.inputstream), null)
	}.pipe((vf:VirtualFile, callback:(VirtualFile, Exception) => Unit) => {
		callback(VirtualFile(vf.name + "-yoyo", vf.path, vf.encoding, vf.inputstream), null)
	}, 20).pipe{(vf:VirtualFile, callback:(VirtualFile, Exception) => Unit) =>
		callback(VirtualFile(vf.name + "-wkwkw", vf.path, vf.encoding, vf.inputstream), null)
	}.toStream

	val stream = Await.result(streamFuture, Duration.Inf)

	stream foreach{v => println(v.name)}

	system.terminate()
}