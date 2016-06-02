package xyz.rura.labs.io

import org.scalatest._

import play.api.libs.json._

import org.apache.http.impl.client.CloseableHttpClient
import org.apache.http.client.methods.CloseableHttpResponse
import org.apache.http.HttpHost
import org.apache.http.HttpRequest
import org.apache.http.protocol.HttpContext
import org.apache.http.conn.ClientConnectionManager
import org.apache.http.params.HttpParams
import org.apache.http.message.BasicHttpResponse
import org.apache.http.message.BasicStatusLine
import org.apache.http.HttpVersion
import org.apache.http.HttpEntity
import org.apache.http.entity.StringEntity

class HttpStreamSpec extends FlatSpec with Matchers 
{
    // setup mocking
    val client = new CloseableHttpClient() {
        override def doExecute(target:HttpHost, request:HttpRequest, context:HttpContext):CloseableHttpResponse = {
            //println(request)
            if(request.getRequestLine().getMethod().equals("GET") && 
                request.getRequestLine().getUri().equals("http://localhost/api/dummy.json")) {
                return new BasicHttpResponse(new BasicStatusLine(HttpVersion.HTTP_1_1, 200, "OK")) with CloseableHttpResponse {
                    override def close():Unit = {}

                    override def getEntity():HttpEntity = new StringEntity("""
                        {
                            "message": "hello world"
                        }
                    """)
                }
            } else if(request.getRequestLine().getMethod().equals("POST") && 
                request.getRequestLine().getUri().equals("http://localhost/api/dummy.json")) {
                return new BasicHttpResponse(new BasicStatusLine(HttpVersion.HTTP_1_1, 200, "OK")) with CloseableHttpResponse {
                    override def close():Unit = {}

                    override def getEntity():HttpEntity = new StringEntity("""
                        {
                            "status": "success"
                        }
                    """)
                }
            }

            return null
        }

        override def getConnectionManager():ClientConnectionManager = null
        override def getParams():HttpParams = null
        override def close():Unit = {}
    }

	var stream:AsyncStream = null
	
  	"Http Stream" should "read input" in {
  		stream = HttpStream.src("http://localhost/api/dummy.json", client)

    	stream.isEnd should === (false)
  	}

  	it should "write output" in {
  		stream = stream.pipe(HttpStream.dest("http://localhost/api/dummy.json", client))

  		stream.isEnd should === (true)	
  	}

  	it should "throw end of input exception" in {
  		intercept[Exception] {
  			stream.pipe(HttpStream.dest("http://localhost/api/dummy.json", client))
  		}
  	}
}