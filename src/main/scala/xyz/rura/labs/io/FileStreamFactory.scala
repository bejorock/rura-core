package xyz.rura.labs.io

import java.io._
import java.nio.file.FileSystems

import akka.actor.ActorSystem

import scala.io.Source
import scala.collection.JavaConversions._
import scala.concurrent.Promise

import org.apache.commons.io.IOUtils
import org.apache.commons.io.FileUtils
import org.apache.commons.io.filefilter.WildcardFileFilter
import org.apache.commons.io.filefilter.DirectoryFileFilter
import org.apache.commons.io.filefilter.IOFileFilter
import org.apache.commons.io.filefilter.FileFileFilter

object FileStreamFactory
{
	def src(globs:Array[String])(implicit system:ActorSystem):ReactiveStream = {
		val files = FileUtils.listFiles(new File("."), new FileFileFilter() {
			val matchers = globs map{g => FileSystems.getDefault().getPathMatcher("glob:./" + g)}

			override def accept(file:File):Boolean = {
				return super.accept(file) && matchers.foldLeft(false){(result, m) => result | m.matches(file.toPath())}
			}
		}, DirectoryFileFilter.DIRECTORY)

		val vfs = files map{f => VirtualFile(f.getName(), f.getPath(), Some(VirtualFile.DEFAULT_ENCODING), new FileInputStream(f))}

		// return new AsyncStream(Promise.successful(vfs.iterator).future)
		return new ReactiveStream(vfs)
	}

	def src(glob:String)(implicit system:ActorSystem):ReactiveStream = src(Array(glob))

	def dest(dirName:String):Mapper = new Mapper() {
		val dir = new File(dirName)

		def map(f:VirtualFile, callback:(VirtualFile, Exception) => Unit):Unit = {
			val file = new File(dirName, f.name)
			val out = new FileOutputStream(file)
			
			IOUtils.copy(f.inputstream, out)
			out.close()

			callback(f, null)
		}
	}
}