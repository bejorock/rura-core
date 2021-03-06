package xyz.rura.labs.io

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.select.Elements

import org.apache.http.HttpEntity
import org.apache.http.client.HttpClient
import org.apache.http.impl.client.CloseableHttpClient
import org.apache.http.NameValuePair
import org.apache.http.client.entity.UrlEncodedFormEntity
import org.apache.http.client.methods.CloseableHttpResponse
import org.apache.http.client.methods.HttpGet
import org.apache.http.client.methods.HttpPost
import org.apache.http.impl.client.CloseableHttpClient
import org.apache.http.impl.client.HttpClients
import org.apache.http.message.BasicNameValuePair
import org.apache.http.util.EntityUtils
import org.apache.http.entity.ByteArrayEntity

import scala.io.Source
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.Await
import scala.concurrent.duration._

import org.apache.commons.io.IOUtils
import org.apache.commons.io.FileUtils

import java.util.concurrent.ArrayBlockingQueue

import akka.actor.ActorSystem
import akka.event.Logging

import xyz.rura.labs.util._

object HttpStreamFactory
{
	//private val client = HttpClients.createDefault()

	def stemUrl(url:String):String = {
		val index = url.lastIndexOf("/")
		val length = url.length

		return url.substring(index, length)
	}

	def src(urls:Array[String], client:HttpClient)(implicit system:ActorSystem):ReactiveStream = {
		val vfs = urls map{url => 
			val get = new HttpGet(url)
			val resp = client.execute(get)
			val entity = resp.getEntity()
			val contents = EntityUtils.toString(entity)

			if(resp.getStatusLine().getStatusCode() != 200) {
				throw new Exception("http error")
			}

			// create a virtual file
			VirtualFile(stemUrl(url), url, Some(VirtualFile.DEFAULT_ENCODING), IOUtils.toInputStream(contents))
		}

		return new ReactiveStream(vfs)
	}

	def src(urls:Array[String])(implicit system:ActorSystem):ReactiveStream = {
		val client = HttpClients.createDefault()
		val s = src(urls, client)

		client.close()

		return s
	}

	// GET METHOD ONLY
	def src(url:String, client:HttpClient)(implicit system:ActorSystem):ReactiveStream = src(Array(url), client)

	def src(url:String)(implicit system:ActorSystem):ReactiveStream = src(Array(url))

	def watch(url:String, _client:HttpClient, duration:Duration, interval:Int)(implicit system:ActorSystem):ReactiveStream = {
		//import system.dispatcher
		implicit val ec = system.dispatchers.lookup("rura.akka.dispatcher.threadpool.simple")

		// create input
		val input = new Iterable[VirtualFile]() {
			val client = _client
			val log = Logging(system, this.getClass())

			def iterator = new Iterator[VirtualFile]() {
				val queue = new ArrayBlockingQueue[VirtualFile](1)
				val deadline = duration.isFinite match {
					case false => null
					case true => duration.asInstanceOf[FiniteDuration].fromNow
				}

				Future {
					// fetch the url content here
					var firstRun = true
					while(!isExpired) {
						val get = new HttpGet(url)
						val resp = client.execute(get)
						val entity = resp.getEntity()
						val contents = EntityUtils.toString(entity)

						if(firstRun) {
							firstRun = false
						} else {
							Thread.sleep(interval)
						}

						if(resp.getStatusLine().getStatusCode() != 200) {
							val code = resp.getStatusLine().getStatusCode()
							val reason = resp.getStatusLine().getReasonPhrase()

							log.debug(s"$code fail to fetch content because $reason")
						} else {
							// create a virtual file
							queue.put(VirtualFile(stemUrl(url), url, Some(VirtualFile.DEFAULT_ENCODING), IOUtils.toInputStream(contents)))
						}
					}

					//queue.put(null)
				}

				def isExpired:Boolean = {
					if(!duration.isFinite) {
						return false
					} else {
						return deadline.isOverdue
					}
				}

				def hasNext:Boolean = !isExpired

				def next:VirtualFile = queue.take()
			}
		}

		return new ReactiveStream(input)
	}

	// POST METHOD ONLY
	def dest(url:String, _client:HttpClient):ClassProps[Mapper] = ClassProps(classOf[Dest], url, _client)

	// [TODO] find out how to close client after execution
	def dest(url:String):ClassProps[Mapper] = dest(url, HttpClients.createDefault())

	final class Dest(url:String, client:HttpClient) extends AbstractMapper {
		def map(f:VirtualFile, output:MapperOutput):Unit = {
			val post = new HttpPost(url)
			post.setEntity(new ByteArrayEntity(IOUtils.toByteArray(f.inputstream)))

			val resp = client.execute(post)

			if(resp.getStatusLine().getStatusCode() != 200) {
				output.collect(new Exception("failed to execute http request, return " + resp.getStatusLine().getStatusCode()))
			}
		}
	}
}