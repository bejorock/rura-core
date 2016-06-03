package xyz.rura.labs.io.reactive

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

trait ReactiveProxy 
{
	def output:Future[Iterable[VirtualFile]]
}

class ReactiveProxyImpl(client:ActorRef) extends ReactiveProxy with TypedActor.PostStop
{
	import ReactiveStream.{Output, defaultTimeout}

	private lazy val log = Logging(TypedActor.context.system, TypedActor.context.self)

	def postStop():Unit = log.debug("proxy {} stopped!!!", TypedActor.context.self.path)

	override def output:Future[Iterable[VirtualFile]] = (client ? Output()).mapTo[Iterable[VirtualFile]]
}