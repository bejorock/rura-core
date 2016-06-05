package xyz.rura.labs.io

import akka.actor.ActorSystem

import scala.collection.mutable.ListBuffer
import scala.io.Source
import scala.concurrent.Promise

import org.apache.commons.io.IOUtils
import org.apache.commons.io.FileUtils

import java.io.OutputStream
import java.io.InputStream
import java.io.BufferedReader
import java.io.InputStreamReader

object ConsoleStreamFactory
{
	def src(labels:Array[String])(implicit system:ActorSystem, inOption:Option[InputStream] = None):ReactiveStream = {
		val vfs = labels.zipWithIndex map{case (l, i) => 
			var ln:String = null
			Console.withIn(inOption.map{in => new BufferedReader(new InputStreamReader(in))}.getOrElse(Console.in)) {
				ln = readLine(l + " : ")
			}

			if(ln != null) {
				// create a virtual file
				VirtualFile(l, ".", Some(VirtualFile.DEFAULT_ENCODING), IOUtils.toInputStream(ln))
			} else {
				throw new Exception("invalid input")
			}
		}

		return new ReactiveStream(vfs)
	}

	def dest(implicit o:Option[OutputStream] = None):Mapper = new Mapper() {
		val out = o

		def map(f:VirtualFile, callback:(VirtualFile, Exception) => Unit):Unit = {
			Console.withOut(out.getOrElse(Console.out)) {
				println("file: " + f.name)
				println("path: " + f.path)
				println("encoding: " + f.encoding.getOrElse("None"))
				println("contents: " + IOUtils.toString(f.inputstream))
			}

			// must call callback
			callback(null, null)
		}
	}
}