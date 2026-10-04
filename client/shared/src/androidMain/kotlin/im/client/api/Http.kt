package im.client.api

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod

actual object Http {
    private val client = HttpClient(OkHttp) {
        expectSuccess = false
        // 没有超时配置时，链路半死（TCP 已断但不报错）会让登录、拉会话、取图永久挂起，
        // UI 上表现为按钮点不动、转圈不结束。这里给所有请求兜一个上限。
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 30_000
        }
    }

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
            // 上传大附件可能远超默认 30s，单独放宽（直传对象存储，不占服务端连接）
            timeout {
                requestTimeoutMillis = BIG_OBJECT_TIMEOUT_MS
                socketTimeoutMillis = BIG_OBJECT_TIMEOUT_MS
            }
            setBody(bytes)
        }
        return resp.status.value
    }

    actual suspend fun getBinary(url: String): Pair<Int, ByteArray> {
        val resp = client.request(url) {
            // 取图/下载同样按大对象放宽
            timeout {
                requestTimeoutMillis = BIG_OBJECT_TIMEOUT_MS
                socketTimeoutMillis = BIG_OBJECT_TIMEOUT_MS
            }
        }
        return resp.status.value to resp.readBytes()
    }

    /** 大对象（附件上传/下载）的超时上限 */
    private const val BIG_OBJECT_TIMEOUT_MS = 10 * 60 * 1000L
}
