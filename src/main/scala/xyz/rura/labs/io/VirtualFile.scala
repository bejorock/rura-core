package xyz.rura.labs.io

import java.io.InputStream

trait VirtualFile extends Serializable
{
	def name:String
	def path:String
	def encoding:Option[String]
	def inputstream:InputStream

	def trace(startTime:Long):Unit
	def processingTime:Long
}

object VirtualFile 
{
	val UTF_8 = "UTF-8"	
	val UNICODE = "UNICODE"
	val DEFAULT_ENCODING = UTF_8

	val Empty = VirtualFile(null, null, None, null)

	final case class EOF() extends VirtualFile {
		var _processingTime = 0l

		def name:String = "eof"
		def path:String = "eof"
		def encoding:Option[String] = None
		def inputstream:InputStream = null

		def trace(startTime:Long):Unit = _processingTime = System.nanoTime() - startTime
		def processingTime:Long = _processingTime
	}

	def apply(n:String, p:String, e:Option[String], i:InputStream) = new VirtualFile() {
		var _processingTime = 0l

		def name:String = n
		def path:String = p
		def encoding:Option[String] = e
		def inputstream:InputStream = i

		def trace(startTime:Long):Unit = _processingTime = System.nanoTime() - startTime
		def processingTime:Long = _processingTime
	}
}