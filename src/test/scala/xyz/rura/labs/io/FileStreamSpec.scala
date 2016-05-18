package xyz.rura.labs.io

import org.scalatest._

class FileStreamSpec extends FlatSpec with Matchers 
{
	var stream:Stream = null

	"File Stream" should "read input" in {
		stream = FileStream.src("tmp/src/*.json")

		stream.isEnd should === (false)
	}

	it should "write output" in {
		stream = stream.pipe(FileStream.dest("tmp/dest/"))

		stream.isEnd should === (true)
	}

	it should "throw end of input exception" in {
  		intercept[Exception] {
  			stream.pipe(FileStream.dest("tmp/dest/"))
  		}
  	}
}