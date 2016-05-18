package xyz.rura.labs.io

import java.io._

import scala.collection.mutable.ListBuffer
import scala.io.Source
import scala.concurrent.Promise

object ConsoleStream 
{
	def src(labels:Array[String], defaults:Array[String]):Stream = {
		val vfs = labels.zipWithIndex map{case (l, i) => 
			print(l + ": ")

			val ln = defaults.length match {
				case 0 => readLine()
				case _ => defaults(i)
			}

			if(ln != null) {
				// create a virtual file
				val vf = new VirtualFile() {
					override def name = l
					override def path = "."
					override def encoding = Some(VirtualFile.DEFAULT_ENCODING)
					override def inputstream = new ByteArrayInputStream(ln.getBytes(VirtualFile.DEFAULT_ENCODING))
				}

				vf
			} else {
				throw new Exception("invalid input")
			}
		}

		return new AsyncStream(Promise.successful(vfs.iterator).future)
	}

	def src(labels:Array[String]):Stream = src(labels, Array[String]())

	def src(label:String, default:String):Stream = src(Array(label), Array(default))

	def src(label:String):Stream = src(Array(label), Array[String]())

	def dest:Map = new Map() {
		def map(f:VirtualFile, callback:(VirtualFile, Exception) => Unit):Unit = {
			println("file: " + f.name)
			println("path: " + f.path)
			println("encoding: " + f.encoding.getOrElse("None"))
			println("contents: " + Source.fromInputStream(f.inputstream, f.encoding.getOrElse("UTF-8")).mkString)
		}
	}
}