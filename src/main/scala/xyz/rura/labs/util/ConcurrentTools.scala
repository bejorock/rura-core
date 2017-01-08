package xyz.rura.labs.util

import scala.concurrent.Future
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext

object ConcurrentTools
{
	def awaitFutures[T](futures:Iterable[Future[T]])(implicit ec:ExecutionContext):Unit = {
		Await.ready(Future{
			var tmp:Iterable[Future[T]] = Iterable.empty[Future[T]]
			do {
				tmp = futures filter{f => !f.isCompleted}
			} while(tmp.size > 0)
		}, Duration.Inf)
	}
}