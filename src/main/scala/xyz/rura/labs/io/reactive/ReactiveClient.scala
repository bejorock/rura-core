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
import scala.util.Try

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

class  ReactiveClient(input:Iterator[VirtualFile], target:ActorSelection) extends Actor with ActorLogging
{
	import ReactiveStream.{Request, Response, Error, Output, WorkerNotReady, EOF, Ping, Pong}
	//import context.dispatcher

	private implicit val ec = context.system.dispatchers.lookup("rura.akka.dispatcher.threadpool.simple")

	// output cache
	private lazy val config = context.system.settings.config
	private lazy val output:ReactiveOutput = new IndirectReactiveOutput(config.getInt("rura.reactive-stream.output.cache-timeout"), config.getInt("rura.reactive-stream.output.max-result-cache"), config.getInt("rura.reactive-stream.output.max-error-cache"))
	private lazy val slots = new ArrayBlockingQueue[Boolean](config.getInt("rura.reactive-stream.client.input-slots"))
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
				Try {
					val tracerContext = Tracer.currentContext

					val timestampAfterProcessing = RelativeNanoTimestamp.now
					val processingTime = timestampAfterProcessing - tracerContext.startTimestamp

					metrics.successTime.record(processingTime.nanos)

					tracerContext.finish()

					// trace file
					out.trace(tracerContext.startTimestamp.nanos)
				}
			}

			// release a slot
			slots.poll()

			// add to queue
			output.put(out)
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
			var startTime = System.nanoTime()

			if(ReactiveStream.kamonEnabled) {
				Try {
					val tracerContext = Tracer.currentContext
					val timestampAfterProcessing = RelativeNanoTimestamp.now
					val processingTime = timestampAfterProcessing - tracerContext.startTimestamp

					metrics.errorTime.record(processingTime.nanos)
					metrics.errors.increment()

					tracerContext.finish()

					startTime = tracerContext.startTimestamp.nanos
				}
			}

			slots.poll()

			err match {
				case e: ReactiveException => {
					if(ReactiveStream.kamonEnabled) {
						// mark trace time
						e.trace(startTime)
						e.vf.trace(startTime)
					}

					output.put(e)
				}
				case e: Exception => {}
			}

			log.error(err, "cannot process message at {}", sender.path)
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