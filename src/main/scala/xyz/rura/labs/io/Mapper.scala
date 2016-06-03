package xyz.rura.labs.io

trait Mapper extends Serializable
{
	def map(f:VirtualFile, callback:(VirtualFile, Exception) => Unit):Unit
}

object Mapper
{
	lazy val empty = new Mapper() {
		def map(f:VirtualFile, callback:(VirtualFile, Exception) => Unit):Unit = {}		
	}
}