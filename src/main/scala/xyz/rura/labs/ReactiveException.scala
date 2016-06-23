package xyz.rura.labs

import xyz.rura.labs.io._

case class ReactiveException(vf:VirtualFile, cause:Throwable) extends Exception(s"cannot proceess message ${vf.name} at ${vf.path}", cause)

object ReactiveException
{
	val Empty = new ReactiveException(VirtualFile.Empty, new Error())
}