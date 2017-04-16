package xyz.rura.labs

import xyz.rura.labs.io._

case class ReactiveException(vf:VirtualFile, cause:Throwable) extends Exception(s"cannot proceess message ${vf.name} at ${vf.path}", cause) 
{
	private var _processingTime = 0l

	def trace(startTime:Long):Unit = _processingTime = System.nanoTime() - startTime

	def processingTime:Long = _processingTime
}

object ReactiveException
{
	val Empty = new ReactiveException(VirtualFile.Empty, new Error())
}