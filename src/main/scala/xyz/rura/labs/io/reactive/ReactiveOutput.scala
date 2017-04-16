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

	def onComplete(code: => Unit):Unit
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

	def onComplete(code: => Unit):Unit = code
	private[reactive] def triggerComplete:Unit = {}

	private[reactive] def put(vf:VirtualFile):Unit = {}

	private[reactive] def put(err:ReactiveException):Unit = {}
}

class IndirectReactiveOutput(DEFAULT_TIMEOUT:Int, maxResultCache:Int, maxErrorCache:Int)(implicit ec:ExecutionContextExecutor) extends ReactiveOutput
{
	//private var isComplete = false
	private var timeout = DEFAULT_TIMEOUT // seconds
	private val resultQueue = new ArrayBlockingQueue[VirtualFile](maxResultCache)
	private val errorQueue = new ArrayBlockingQueue[ReactiveException](maxErrorCache)

	private val _resultCache = Promise[Iterable[VirtualFile]]()
	private val _errorCache = Promise[Iterable[ReactiveException]]()
	private val _complete = Promise[Boolean]()
	private val _completeFuture = _complete.future
	//private val _completeCache = ListBuffer[() => Unit]()

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

	def onComplete(code: => Unit):Unit = synchronized {
		if(_completeFuture.isCompleted) {
			code
		} else {
			_completeFuture onSuccess {
				case status => code
			}
		}
	}

	private[reactive] def triggerComplete:Unit = synchronized {
		//isComplete = true
		//_completeCache foreach{code => code()}
		_complete success true
	}

	private[reactive] def put(vf:VirtualFile):Unit = resultQueue.synchronized {
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

	private[reactive] def put(err:ReactiveException):Unit = errorQueue.synchronized {
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