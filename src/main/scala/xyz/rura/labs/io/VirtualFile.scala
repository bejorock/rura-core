package xyz.rura.labs.io

import java.io.InputStream

trait VirtualFile extends Serializable
{
	def name:String
	def path:String
	def encoding:Option[String]
	def inputstream:InputStream
}

object VirtualFile 
{
	val UTF_8 = "UTF-8"	
	val UNICODE = "UNICODE"
	val DEFAULT_ENCODING = UTF_8

	final case class EOF() extends VirtualFile {
		def name:String = "eof"
		def path:String = "eof"
		def encoding:Option[String] = None
		def inputstream:InputStream = null
	}

	def apply(n:String, p:String, e:Option[String], i:InputStream) = new VirtualFile() {
		def name:String = n
		def path:String = p
		def encoding:Option[String] = e
		def inputstream:InputStream = i
	}
}