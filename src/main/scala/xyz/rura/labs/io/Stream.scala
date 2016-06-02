package xyz.rura.labs.io

trait Stream[T]
{
	def pipe(m:Map):T

	def isEnd:Boolean
}