package xyz.rura.labs.util

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

object ClientFactory
{
	lazy val httpClient = {
		val defaultRequestConfig = RequestConfig.custom().setSocketTimeout(5000).setConnectTimeout(5000).setConnectionRequestTimeout(5000).setStaleConnectionCheckEnabled(true).build()
		val connectionManager = new PoolingHttpClientConnectionManager()
		connectionManager.setMaxTotal(250)
		connectionManager.setDefaultMaxPerRoute(20)

		HttpClients.custom().setDefaultRequestConfig(defaultRequestConfig).setConnectionManager(connectionManager).build()
	}
}