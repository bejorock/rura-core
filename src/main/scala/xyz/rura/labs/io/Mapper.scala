package xyz.rura.labs.io

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
import org.apache.http.client.config.RequestConfig
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager

import com.mongodb.casbah.Imports._

import xyz.rura.labs.util._

class MapperOutput(handler:PartialFunction[AnyRef, Unit])
{
	def collect(outFile:VirtualFile):Unit = handler(outFile)

	def collect(throwable:Throwable):Unit = handler(throwable)
}

object MapperOutput
{
	def apply(handler:PartialFunction[AnyRef, Unit]):MapperOutput = new MapperOutput(handler)
}

trait Mapper extends Serializable
{
	def onStart():Unit

	def onStop():Unit

	//def map(f:VirtualFile, callback:(VirtualFile, Exception) => Unit):Unit
	def map(f:VirtualFile, output:MapperOutput):Unit

	def map(f:VirtualFile)(handler:PartialFunction[AnyRef, Unit]):Unit
}

abstract class AbstractMapper extends Mapper 
{
	def onStart():Unit = {}

	def onStop():Unit = {}

	final def map(f:VirtualFile)(handler:PartialFunction[AnyRef, Unit]):Unit = map(f, MapperOutput(handler))
}

abstract class HttpMapper extends Mapper
{
	def client = ClientFactory.httpClient

	def onStart():Unit = {}

	def onStop():Unit = {}

	def get(url:String):String = {
		val get = new HttpGet(url)
		val resp = client.execute(get)
		val entity = resp.getEntity()
		val contents = EntityUtils.toString(entity)

		if(resp.getStatusLine().getStatusCode() != 200) {
			throw new Exception(s"failed to get page $url")
		}

		return contents
	}

	def post(url:String, data:Map[String, String]):Unit = {}

	def put(url:String, data:Map[String, String]):Unit = {}

	def delete(url:String):Unit = {}

	final def map(f:VirtualFile)(handler:PartialFunction[AnyRef, Unit]):Unit = map(f, MapperOutput(handler))
}

abstract class MongoMapper(host:String, port:Int) extends Mapper
{
	private lazy val _client = MongoClient(host, port)

	def client = _client

	def onStart():Unit = {}

	def onStop():Unit = _client.close()

	final def map(f:VirtualFile)(handler:PartialFunction[AnyRef, Unit]):Unit = map(f, MapperOutput(handler))
}

object Mapper
{
	lazy val empty:Mapper = new AbstractMapper() {
		def map(f:VirtualFile, output:MapperOutput):Unit = {}
	}
}