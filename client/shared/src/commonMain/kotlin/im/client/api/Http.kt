package im.client.api

/** expect/actual：各平台用 Ktor 发 HTTP */
expect object Http {
    suspend fun execute(method: String, url: String, body: String?, token: String?): Pair<Int, String>
    suspend fun putBinary(url: String, bytes: ByteArray): Int
}
