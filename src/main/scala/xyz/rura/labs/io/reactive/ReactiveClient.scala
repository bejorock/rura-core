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
import akka.actor.Cancellable

import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.Await
import java.util.concurrent.TimeUnit
//import scala.concurrent.duration._
import scala.collection.immutable.{Stream => ScalaStream}
import scala.collection.mutable.ListBuffer
//import scala.concurrent.ExecutionContext.Implicits.global
import scala.reflect.ClassTag
import scala.io.AnsiColor
import scala.language.postfixOps
import scala.concurrent.ExecutionContextExecutor

import org.apache.commons.io.IOUtils

import java.io.InputStream
import java.io.ObjectInputStream
import java.io.ByteArrayInputStream
import java.io.ObjectOutputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.ArrayBlockingQueue

import xyz.rura.labs._
import xyz.rura.labs.io._
import xyz.rura.labs.io.monitor._

import kamon.trace.Tracer
import kamon.trace.EmptyTraceContext
import kamon.Kamon
import kamon.metric.instrument.Time
import kamon.util.RelativeNanoTimestamp
import kamon.util.NanoInterval

trait ReactiveOutput extends Serializable
{
	def result:Future[Iterable[VirtualFile]]
	def error:Future[Iterable[ReactiveException]]

	def nonBlocking:ReactiveOutput

	def blocking:ReactiveOutput

	def onComplete(code: () => Unit):Unit
	private[reactive] def triggerComplete:Unit

	private[reactive] def put(vf:VirtualFile):Unit

	private[reactive] def put(err:ReactiveException):Unit
}

class DirectReactiveOutput(iterable:Iterable[VirtualFile]) extends ReactiveOutput
{
	def result:Future[Iterable[VirtualFile]] = Promise.successful(iterable).future
	def error:Future[Iterable[ReactiveException]] = Promise.successful(List.empty[ReactiveException]).future

	def nonBlocking:ReactiveOutput = this

	def blocking:ReactiveOutput = this

	def onComplete(code: () => Unit):Unit = code()
	private[reactive] def triggerComplete:Unit = {}

	private[reactive] def put(vf:VirtualFile):Unit = {}

	private[reactive] def put(err:ReactiveException):Unit = {}
}

class IndirectReactiveOutput(implicit ec:ExecutionContextExecutor) extends ReactiveOutput
{
	private val DEFAULT_TIMEOUT = 5

	private var isComplete = false
	private var timeout = DEFAULT_TIMEOUT // seconds
	private val resultQueue = new ArrayBlockingQueue[VirtualFile](10000)
	private val errorQueue = new ArrayBlockingQueue[ReactiveException](10000)

	private val _resultCache = Promise[Iterable[VirtualFile]]()
	private val _errorCache = Promise[Iterable[ReactiveException]]()
	private val _completeCache = ListBuffer[() => Unit]()

	// setup output cache
	Future {
		_resultCache success new Iterable[VirtualFile]() {
			private var vf:VirtualFile = VirtualFile.Empty
			private val _iterator = new Iterator[VirtualFile]() {
				vf = resultQueue.take()

				def hasNext:Boolean = {
					if(vf.isInstanceOf[VirtualFile.EOF]) {
						return false
					} else {
						return true
					}
				}

				def next():VirtualFile = {
					var oldVf = vf

					vf = resultQueue.take()

					return oldVf
				}

				override def hasDefiniteSize = vf.isInstanceOf[VirtualFile.EOF]
			}

			def iterator = _iterator

			override def hasDefiniteSize = vf.isInstanceOf[VirtualFile.EOF]
		} 
	}

	// setup error cache
	Future {
		_errorCache success new Iterable[ReactiveException]() {
			private var err:ReactiveException = ReactiveException.Empty
			private val _iterator = new Iterator[ReactiveException]() {
				err = errorQueue.take()

				def hasNext:Boolean = {
					if(err.vf.isInstanceOf[VirtualFile.EOF]) {
						return false
					} else {
						return true
					}
				}

				def next():ReactiveException = {
					var oldErr = err

					err = errorQueue.take()

					return oldErr
				}

				override def hasDefiniteSize = err.vf.isInstanceOf[VirtualFile.EOF]
			}

			def iterator = _iterator

			override def hasDefiniteSize = err.vf.isInstanceOf[VirtualFile.EOF]
		} 
	}

	def result = _resultCache.future
	def error = _errorCache.future

	def nonBlocking:ReactiveOutput = {
		timeout = 0

		return this
	}

	def blocking:ReactiveOutput = {
		timeout = DEFAULT_TIMEOUT

		return this
	}

	def onComplete(code: () => Unit):Unit = synchronized {
		if(isComplete) {
			code()
		} else {
			_completeCache += code
		}
	}
	private[reactive] def triggerComplete:Unit = synchronized {
		isComplete = true
		_completeCache foreach{code => code()}
	}

	private[reactive] def put(vf:VirtualFile):Unit = synchronized {
		// try to offer result for certain of time
		val success = timeout match {
			case 0 => resultQueue.offer(vf)
			case t => resultQueue.offer(vf, timeout, TimeUnit.SECONDS)
		}

		if(!success) {
			// try to remove old data and reinsert the file
			resultQueue.take()
			resultQueue.put(vf)
		}
	}

	private[reactive] def put(err:ReactiveException):Unit = synchronized {
		val success = timeout match {
			case 0 => errorQueue.offer(err)
			case t => errorQueue.offer(err, timeout, TimeUnit.SECONDS)
		}

		if(!success) {
			// try to remove old data and reinsert the error
			errorQueue.take()
			errorQueue.put(err)
		}
	}
}

class ReactiveClient(input:Iterator[VirtualFile], target:ActorSelection) extends Actor with ActorLogging
{
	import ReactiveStream.{Request, Response, Error, Output, WorkerNotReady, EOF, Ping, Pong}
	import context.dispatcher

	// output cache
	private lazy val output:ReactiveOutput = new IndirectReactiveOutput()
	private lazy val slots = new ArrayBlockingQueue[Boolean](1000)
	private lazy val metrics = Kamon.metrics.entity(ReactiveClientMetrics, context.system.name + "_" + self.path.elements.mkString("_"))

	override def preStart():Unit = {
		log.debug("starting client {}...", self.path)

		Future {
			try { 
				Thread.sleep(1000)
				// start sending input
				input foreach{vf => 
					// fill in slots
					slots.put(true)

					Tracer.withNewContext("request-trace") {
						target ! Request(vf)
					}
				}

				// send eof broadcast
				Thread.sleep(1000)

				target ! EOF()
			} catch {
			  	case e: Exception => log.error(e, "error sending input at {}", self.path)
			}
		}
	}

	override def postStop():Unit = {
		Kamon.metrics.removeEntity(context.system.name + "_" + self.path.elements.mkString("_"), "reactive-client")

		log.debug("client {} stopped!!!", self.path)
	}

	def receive = {
		case Response(out) => {

			if(ReactiveStream.kamonEnabled) {
				val tracerContext = Tracer.currentContext

				val timestampAfterProcessing = RelativeNanoTimestamp.now
				val processingTime = timestampAfterProcessing - tracerContext.startTimestamp

				metrics.successTime.record(processingTime.nanos)

				tracerContext.finish()
			}

			//Future {
				// release a slot
				slots.poll()

				// add to queue
				//output.resultQueue.put(out)
				output.put(out)
			//}
		}

		case EOF() => {
		//Future {
			// release a slot
			slots.poll()

			// add eof to queue
			output.put(VirtualFile.EOF())	
			output.put(new ReactiveException(VirtualFile.EOF(), null))	

			// trigger complete
			output.triggerComplete
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

		case Error(err) => {
			val session = sender
			
			if(ReactiveStream.kamonEnabled) {
				val tracerContext = Tracer.currentContext

				val timestampAfterProcessing = RelativeNanoTimestamp.now
				val processingTime = timestampAfterProcessing - tracerContext.startTimestamp

				metrics.errorTime.record(processingTime.nanos)
				metrics.errors.increment()

				tracerContext.finish()
			}

			//Future {
				// release a slot
				slots.poll()

				err match {
					case e: ReactiveException => output.put(e)
					case e: Exception => {}
				}

				log.error(err, "cannot process message at {}", sender.path)
			//}
		}

		case Output() => { 
			val session = sender

			Future {
				//output pipeTo session
				session ! output

				log.debug("output sent to proxy")
			}
		}

		case Pong() => log.debug("ping! pong!")
	}
}