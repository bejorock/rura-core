package xyz.rura.labs.io

class BufferOutput extends Map 
{
	private var out:VirtualFile = null

	def map(f:VirtualFile, callback:(VirtualFile, Exception) => Unit):Unit = {
		out = f
	}

	def file:VirtualFile = out
}