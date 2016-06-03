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

import scala.concurrent.Future
import scala.concurrent.Promise
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

import xyz.rura.labs.io._

abstract class AbstractReactiveWorker extends Actor with ActorLogging
{
	import ReactiveStream.{Request, Response, Error, SetupWorker, ResetWorker, WorkerNotReady, defaultTimeout, EOF}
	import context.dispatcher

	def eof():Unit = {}

	def request(vf:VirtualFile):Unit

	def reset(eofOrigin:Boolean):Unit

	def setup(mapper:Mapper, nextTarget:Option[ActorSelection], num:Int):Unit

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
		case EOF() => {
			eof()

			self.tell(ResetWorker(true), sender)
		}

		case Request(vf) => request(vf)

		case ResetWorker(eofOrigin) => {
			reset(eofOrigin)

			// actor becomes inactive
			context.unbecome()
		}
	}
}