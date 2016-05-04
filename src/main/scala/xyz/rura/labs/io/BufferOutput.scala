package xyz.rura.labs.io

import scala.collection.mutable.ListBuffer

class BufferOutput extends Map 
{
	private var out:ListBuffer[VirtualFile] = ListBuffer[VirtualFile]()

	def map(f:VirtualFile, callback:(VirtualFile, Exception) => Unit):Unit = {
		out += f
	}

	def buffer = out
}