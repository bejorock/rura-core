package xyz.rura.labs.io

trait Map extends Serializable
{
	def map(f:VirtualFile, callback:(VirtualFile, Exception) => Unit):Unit
}

object Map
{
	lazy val empty = new Map() {
		def map(f:VirtualFile, callback:(VirtualFile, Exception) => Unit):Unit = {}		
	}
}