package xyz.rura.labs.io

trait Map
{
	def map(f:VirtualFile, callback:(VirtualFile, Exception) => Unit):Unit
}