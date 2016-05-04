package xyz.rura.labs.io

import java.io._
import java.nio.file.FileSystems

import scala.io.Source
import scala.collection.JavaConversions._

import org.apache.commons.io.IOUtils
import org.apache.commons.io.FileUtils
import org.apache.commons.io.filefilter.WildcardFileFilter
import org.apache.commons.io.filefilter.DirectoryFileFilter
import org.apache.commons.io.filefilter.IOFileFilter
import org.apache.commons.io.filefilter.FileFileFilter

object FileStream 
{
	def src(globs:Array[String]):Stream = {
		val files = FileUtils.listFiles(new File("."), new FileFileFilter() {
			val matchers = globs map{g => FileSystems.getDefault().getPathMatcher("glob:./" + g)}

			override def accept(file:File):Boolean = {
				return super.accept(file) && matchers.foldLeft(false){(result, m) => result | m.matches(file.toPath())}
			}
		}, DirectoryFileFilter.DIRECTORY)

		val vfs = files map{f => new VirtualFile() {
			override def name = f.getName()
			override def path = f.getPath()
			override def encoding = Some(VirtualFile.DEFAULT_ENCODING)
			override def inputstream = new FileInputStream(f)
		}}

		return new BasicStream(vfs.toList)
	}

	def src(glob:String):Stream = src(Array(glob))

	def dest(dirName:String):Map = new Map() {
		val dir = new File(dirName)

		def map(f:VirtualFile, callback:(VirtualFile, Exception) => Unit):Unit = {
			val file = new File(dirName, f.name)
			val out = new FileOutputStream(file)
			
			IOUtils.copy(f.inputstream, out)
			out.close()
		}
	}
}