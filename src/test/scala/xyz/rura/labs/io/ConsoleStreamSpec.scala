package xyz.rura.labs.io

import org.scalatest._

import scala.io.Source

class ConsoleStreamSpec extends FlatSpec with Matchers 
{
	var stream:Stream = null
	
  	"Console Stream" should "read input" in {
  		stream = ConsoleStream.src("name", "rana")

    	stream.isEnd should === (false)
  	}

    it should "have content rana" in {
        stream.pipe(new Map() {
            def map(f:VirtualFile, callback:(VirtualFile, Exception) => Unit):Unit = {
                Source.fromInputStream(f.inputstream).mkString should === ("rana")

                callback(f, null)
            }
        })
    }

  	it should "write output" in {
  		stream.pipe(ConsoleStream.dest)

  		stream.isEnd should === (true)
  	}

  	it should "throw end of input exception" in {
  		intercept[Exception] {
  			stream.pipe(ConsoleStream.dest)
  		}
  	}
}