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
//import scala.concurrent.duration._
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.ArrayBlockingQueue

import xyz.rura.labs.io._
import xyz.rura.labs.io.monitor._

import kamon.trace.Tracer
import kamon.trace.EmptyTraceContext
import kamon.Kamon
import kamon.metric.instrument.Time
import kamon.util.RelativeNanoTimestamp
import kamon.util.NanoInterval

class ReactiveClient(input:Iterator[VirtualFile], target:ActorSelection) extends Actor with ActorLogging
{
	import ReactiveStream.{Request, Response, Error, Output, WorkerNotReady, EOF, Ping, Pong}
	import context.dispatcher

	private var queue = new ArrayBlockingQueue[VirtualFile](1000)
	private lazy val slots = new ArrayBlockingQueue[Boolean](1000)
	private lazy val metrics = Kamon.metrics.entity(ReactiveClientMetrics, context.system.name + "_" + self.path.elements.mkString("_"))

	private def output = Future {
		new Iterable[VirtualFile]() {
			var vf:VirtualFile = VirtualFile.Empty

			def iterator = new Iterator[VirtualFile]() {
				vf = queue.take()

				def hasNext:Boolean = {
					if(vf.isInstanceOf[VirtualFile.EOF]) {
						return false
					} else {
						return true
					}
				}

				def next():VirtualFile = {
					var oldVf = vf

					vf = queue.take()

					return oldVf
				}

				override def hasDefiniteSize = vf.isInstanceOf[VirtualFile.EOF]
			}

			override def hasDefiniteSize = vf.isInstanceOf[VirtualFile.EOF]
		}
	}

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
			  	case e: Exception => e.printStackTrace()
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

			Future {
				//tracerContext.finish()

				//log.debug("got response {}", out.name)
				
				// release a slot
				slots.poll()

				// add to queue
				queue.put(out)
			}
		}

		case EOF() => Future {
			// release a slot
			slots.poll()

			// add eof to queue
			queue.put(VirtualFile.EOF())	
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

			Future {
				// release a slot
				slots.poll()

				log.error(err, "cannot process message at {}", sender.path)
			}
		}

		case Output() => { 
			val session = sender

			Future {
				output pipeTo session

				log.debug("output sent to proxy")
			}
		}

		case Pong() => log.debug("ping! pong!")
	}
}