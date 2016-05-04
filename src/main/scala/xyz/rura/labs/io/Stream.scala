package xyz.rura.labs.io

trait Stream
{
	def pipe(m:Map):Stream

	def isEnd:Boolean
}