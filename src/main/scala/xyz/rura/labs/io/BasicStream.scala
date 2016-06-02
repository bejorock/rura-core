package xyz.rura.labs.io

import scala.collection.mutable.ListBuffer

class BasicStream(var input:List[VirtualFile]) extends Stream[BasicStream]
{
	override def pipe(m:Map):BasicStream = {
		if(input.size < 1) {
			throw new Exception("end of stream!!!")
		}

		val output = ListBuffer[VirtualFile]()

		input foreach{i => m.map(i, (o:VirtualFile, e:Exception) => {
			if(e != null) {
				throw e
			}

			output += o
		})}

		if(output.size > 0) {
			return new BasicStream(output.toList)
		} else {
			return new BasicStream(List.empty) {
				override def pipe(m:Map):BasicStream = throw new Exception("end of stream!!!")
				override def isEnd:Boolean = true
			}
		}

		/*input = output.toList
		output = ListBuffer[VirtualFile]()

		return this*/
	}

	override def isEnd:Boolean = input.size < 1
}