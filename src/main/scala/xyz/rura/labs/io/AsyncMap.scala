package xyz.rura.labs.io

import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.ExecutionContext.Implicits.global

trait AsyncMap
{
	def map(f:VirtualFile, p:Promise[VirtualFile]):Unit
}