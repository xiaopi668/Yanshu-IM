package im.client.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod

actual object Http {
    private val client = HttpClient(OkHttp) { expectSuccess = false }

    actual suspend fun execute(method: String, url: String, body: String?, token: String?): Pair<Int, String> {
        val resp = client.request(url) {
            this.method = when (method) {
                "POST" -> HttpMethod.Post
                "PUT" -> HttpMethod.Put
                "DELETE" -> HttpMethod.Delete
                else -> HttpMethod.Get
            }
            if (token != null) header(HttpHeaders.Authorization, "Bearer $token")
            if (body != null) {
                header(HttpHeaders.ContentType, "application/json")
                setBody(body.encodeToByteArray())
            }
        }
        return resp.status.value to resp.bodyAsText()
    }

    actual suspend fun putBinary(url: String, bytes: ByteArray): Int {
        val resp = client.request(url) {
            this.method = io.ktor.http.HttpMethod.Put
            setBody(bytes)
        }
        return resp.status.value
    }
}
