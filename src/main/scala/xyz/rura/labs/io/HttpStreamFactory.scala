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
import scala.concurrent.Promise

import org.apache.commons.io.IOUtils
import org.apache.commons.io.FileUtils

import akka.actor.ActorSystem

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

	// POST METHOD ONLY
	def dest(url:String, _client:HttpClient):Mapper = new Mapper() {
		val client = _client

		def map(f:VirtualFile, callback:(VirtualFile, Exception) => Unit):Unit = {
			val post = new HttpPost(url)
			post.setEntity(new ByteArrayEntity(IOUtils.toByteArray(f.inputstream)))

			val resp = client.execute(post)

			if(resp.getStatusLine().getStatusCode() != 200) {
				//throw new Exception("failed to execute http request, return " + resp.getStatusLine().getStatusCode())

				callback(null, new Exception("failed to execute http request, return " + resp.getStatusLine().getStatusCode()))
			} else {
				callback(null, null)
			}
		}
	}

	// [TODO] find out how to close client after execution
	def dest(url:String):Mapper = dest(url, HttpClients.createDefault())
}