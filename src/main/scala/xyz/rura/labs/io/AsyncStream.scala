package xyz.rura.labs.io

import scala.collection.mutable.ListBuffer
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Await
import scala.concurrent.duration._

class AsyncStream(tmp:Future[Iterator[VirtualFile]]) extends Stream
{
	private var output = ListBuffer[VirtualFile]()
	private var input = Await.result(tmp, Duration.Inf)

	override def pipe(m:Map):Stream = synchronized {
		// test that input is empty
		if(input.isEmpty) {
			throw new Exception("end of stream!!!")
		}

		// prepare output promise
		val promise = Promise[Iterator[VirtualFile]]()

		val mappers = ListBuffer[Future[Boolean]]()
		// start mapping
		input foreach{i => 
			mappers += Future{
				m.map(i, (o:VirtualFile, e:Exception) => {
					if(e != null) {
						throw e
					}

					output += o
				})

				true
			}
		}

		Await.result(Future.sequence(mappers.toList), 2 minutes)

		if(output.size > 0) {
			promise success output.iterator

			return new AsyncStream(promise.future)
		} else {
			return new Stream() {
				override def pipe(m:Map):Stream = throw new Exception("end of stream!!!")
				override def isEnd:Boolean = true
			}
		}
	}

	override def isEnd:Boolean = synchronized {
		input.isEmpty
	}
}