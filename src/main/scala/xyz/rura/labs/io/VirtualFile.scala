package xyz.rura.labs.io

import java.io.InputStream

trait VirtualFile
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
}