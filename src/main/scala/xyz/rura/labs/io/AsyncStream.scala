package xyz.rura.labs.io

import scala.collection.mutable.ListBuffer
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Await
import scala.concurrent.duration._

import scala.util.{Success, Failure}

import akka.actor.ActorSystem
import akka.actor.ActorRef
import akka.pattern.ask
import akka.util.Timeout
import akka.routing.RoundRobinPool
import akka.actor.Props
import akka.actor.TypedActor
import akka.actor.TypedProps

import java.io.ObjectOutputStream
import java.io.ByteArrayOutputStream

import xyz.rura.labs.akka.{Worker, WorkerRef, WorkerRefImpl}

class AsyncStream(tmp:Future[Iterator[VirtualFile]]) extends Stream[AsyncStream]
{
	private lazy val input = Await.result(tmp, Duration.Inf)

	override def pipe(m:Map):AsyncStream = synchronized {
		// test that input is empty
		if(input.isEmpty) {
			throw new Exception("end of stream!!!")
		}

		// prepare output promise
		val promise = Promise[Iterator[VirtualFile]]()

		val mappers = ListBuffer[Future[Boolean]]()
		val output = ListBuffer[VirtualFile]()
		// start mapping
		input foreach{i => 
			mappers += Future{
				m.map(i, (o:VirtualFile, e:Exception) => {
					if(e != null) {
						e.printStackTrace()
					} else {
						output += o
					}
				})

				true
			}
		}

		Future.sequence(mappers.toList) onComplete {
			case Success(statuses) => {
				promise success output.iterator
			}

			case Failure(err) => err.printStackTrace()
		}

		return new AsyncStream(promise.future)
	}

	/*def pipe(cmap:(VirtualFile, (VirtualFile, Exception) => Unit) => Unit):AsyncStream = synchronized {
		val m = new Map {
			def map(f:VirtualFile, callback:(VirtualFile, Exception) => Unit):Unit = cmap(f, callback)
		}

		pipe(m)
	}*/

	def pipe(cmap:(VirtualFile, (VirtualFile, Exception) => Unit) => Unit)(implicit system:ActorSystem):AsyncStream = synchronized {
		val m = new Map {
			def map(f:VirtualFile, callback:(VirtualFile, Exception) => Unit):Unit = cmap(f, callback)
		}

		if(input.isEmpty) {
			throw new Exception("end of stream!!!")
		}

		// prepare output promise
		val promise = Promise[Iterator[VirtualFile]]()

		// create actors
		val ref = system.actorOf(RoundRobinPool(20).props(Props[Worker]))
		val workerRef:WorkerRef = TypedActor(system).typedActorOf(TypedProps(classOf[WorkerRef], new WorkerRefImpl(ref)).withTimeout(Timeout(5 minutes)))

		val mappers = ListBuffer[Future[VirtualFile]]()

		// start mapping with actor
		input foreach {i =>
			val out = new ByteArrayOutputStream()
			val writer = new ObjectOutputStream(out)
			writer.writeObject(m)
			writer.flush()
			writer.close()

			try { 
			  	mappers += workerRef.map(i, out.toByteArray())
			} catch {
			  	case e: Exception => {} //e.printStackTrace()
			}

			out.close()	
		}

		Future.sequence(mappers.toList) onComplete {
			case Success(files) => {
				promise success files.iterator
			}

			case Failure(err) => {
				err.printStackTrace()
			}
		}

		return new AsyncStream(promise.future)
	}

	override def isEnd:Boolean = synchronized {
		input.isEmpty
	}
}