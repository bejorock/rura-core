/*package xyz.rura.labs.akka

import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Props
import akka.event.Logging
import akka.actor.ActorLogging
import akka.pattern.ask
import akka.util.Timeout

import scala.concurrent.Await
import scala.concurrent.duration._

import org.apache.commons.io.IOUtils

case class LoadClass(name:String)

class AkkaClassLoader(ref:AkkaClassLoaderRef, parent:ClassLoader) extends ClassLoader(parent)
{
	override def findClass(name:String):Class[_] = {
		try { 
		  	return parent.loadClass(name)
		} catch {
		  	case e: Exception => {
		  		val data = ref.load(name)
        
        		return defineClass(name, data, 0, data.length)
		  	}
		}
    }
}

trait AkkaClassLoaderRef 
{
	def load(name:String):Array[Byte]
}

class AkkaClassLoaderRefImpl(ref:ActorRef) extends AkkaClassLoaderRef
{
	implicit val timeout = Timeout(5 minutes)

	override def load(name:String):Array[Byte] = Await.result((ref ? LoadClass(name)).mapTo[Array[Byte]], Duration.Inf)
}*/