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

import akka.actor.ActorSystem
import akka.testkit.{ TestActors, DefaultTimeout, ImplicitSender, TestKit }
import akka.actor.Props

import scala.concurrent.duration._
import scala.concurrent.Await

import org.apache.commons.io.IOUtils

class HttpStreamSpec(_system:ActorSystem) extends TestKit(_system:ActorSystem) with ImplicitSender with WordSpecLike with Matchers with BeforeAndAfterAll
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

    def this() = this(ActorSystem("HttpStreamSpec"))

    override def beforeAll = {

    }

    override def afterAll = {
        TestKit.shutdownActorSystem(system)
        //system.terminate()
    }

    "Http Stream" must {
        "read input from {urls}" in {
            val factory = HttpStreamFactory.src(Array("http://localhost/api/dummy.json"), client)

            Await.result(factory.toStream, Duration.Inf) foreach{vf =>
                Json.parse(IOUtils.toString(vf.inputstream)).toString should === (Json.parse("""
                    {
                        "message": "hello world"
                    }
                """).toString)
            }
        }

        "write output to {url}" in {
            val factory = HttpStreamFactory.src(Array("http://localhost/api/dummy.json"), client).pipe(HttpStreamFactory.dest("http://localhost/api/dummy.json", client))

            Await.result(factory.toStream, Duration.Inf).size should === (0)
        }

        "throw {exception}" in {
            intercept[Exception] {
                val factory = HttpStreamFactory.src(Array("http://localhost/api/dummy.json"), client)

                Await.result(factory.toStream, Duration.Inf)

                Await.result(factory.toStream, Duration.Inf)                
            }
        }
    }
}