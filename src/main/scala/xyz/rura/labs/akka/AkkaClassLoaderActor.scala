package xyz.rura.labs.akka

import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Props
import akka.event.Logging
import akka.actor.ActorLogging
import akka.pattern.ask
import akka.util.Timeout

import org.apache.commons.io.IOUtils

class AkkaClassLoaderActor extends Actor with ActorLogging
{
	def receive = {
		case className:String => {
			log.info("find class: " + className)

			val classPath = className.replace(".", "/") + ".class"
			val in = this.getClass().getClassLoader().getResourceAsStream(classPath)

			sender ! IOUtils.toByteArray(in)
		}
	}
}