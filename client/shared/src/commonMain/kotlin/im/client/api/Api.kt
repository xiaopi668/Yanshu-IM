package im.client.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

@Serializable
data class LoginResp(val uid: String, val token: String)

@Serializable
data class UserResp(val uid: String, val username: String, val nickname: String, val avatar: String)

@Serializable
data class ConversationResp(val id: String, val type: String, val title: String, val last_seq: Long, val read_seq: Long)

@Serializable
data class FriendResp(val uid: String, val username: String, val nickname: String, val avatar: String)

private val json = Json { ignoreUnknownKeys = true }

/** logic HTTP API 封装 */
class Api(private val baseUrl: String) {
    suspend fun register(username: String, password: String, nickname: String): LoginResp {
        val body = json.encodeToString(
            mapOf("username" to username, "password" to password, "nickname" to nickname)
        )
        return request("POST", "/v1/register", body)
    }

    suspend fun login(username: String, password: String, platform: String): LoginResp {
        val body = json.encodeToString(
            mapOf("username" to username, "password" to password, "platform" to platform)
        )
        return request("POST", "/v1/login", body)
    }

    suspend fun me(token: String): UserResp =
        request("GET", "/v1/me", null, token)

    suspend fun friends(token: String): List<FriendResp> =
        request("GET", "/v1/friends", null, token)

    suspend fun createSingle(token: String, peerUid: String): String {
        val resp = request<Map<String, String>>("POST", "/v1/conversations/single", "{\"peer_uid\":\"$peerUid\"}", token)
        return resp["id"]!!
    }

    suspend fun createGroup(token: String, name: String, members: List<String>): String {
        val membersJson = members.joinToString(",") { "\"$it\"" }
        val resp = request<Map<String, String>>(
            "POST", "/v1/conversations/group",
            "{\"name\":\"$name\",\"members\":[$membersJson]}", token,
        )
        return resp["id"]!!
    }

    suspend fun conversations(token: String): List<ConversationResp> =
        request("GET", "/v1/conversations", null, token)

    suspend fun history(token: String, conversationId: String, beforeSeq: Long): List<ConversationHistoryMsg> {
        val text = rawRequest("GET", "/v1/conversations/$conversationId/history?before_seq=$beforeSeq", null, token)
        val msgs = Json.parseToJsonElement(text).jsonObject["msgs"]?.jsonArray ?: return emptyList()
        return msgs.map { m ->
            val o = m.jsonObject
            ConversationHistoryMsg(
                serverMsgId = o["server_msg_id"]!!.jsonPrimitive.content,
                seq = o["seq"]!!.jsonPrimitive.content.toLong(),
                fromUid = o["from_uid"]!!.jsonPrimitive.content,
                msgType = o["msg_type"]!!.jsonPrimitive.content.toInt(),
                text = o["text"]?.jsonPrimitive?.contentOrNull ?: "",
                sentAt = o["sent_at"]!!.jsonPrimitive.content.toLong(),
            )
        }
    }

    /** 上传附件：返回下载用的相对 key */
    suspend fun uploadAttachment(token: String, kind: String, bytes: ByteArray): String {
        val cred = request<Map<String, String>>("POST", "/v1/upload-token", "{\"kind\":\"$kind\"}", token)
        Http.putBinary(cred["put_url"]!!, bytes)
        return cred["key"]!!
    }

    /** 下载地址（经 logic 预签名重定向） */
    fun downloadUrl(token: String, key: String): String = "$baseUrl/v1/download?key=$key&token=$token"

    private suspend inline fun <reified T> request(method: String, path: String, body: String?, token: String? = null): T {
        val text = rawRequest(method, path, body, token)
        return json.decodeFromString(text)
    }

    private suspend fun rawRequest(method: String, path: String, body: String?, token: String?): String {
        val resp = Http.execute(
            method = method,
            url = "$baseUrl$path",
            body = body,
            token = token,
        )
        if (resp.first >= 400) throw ApiException("HTTP ${resp.first}: ${resp.second}")
        return resp.second
    }
}

class ApiException(message: String) : Exception(message)

@Serializable
data class ConversationHistoryMsg(
    val serverMsgId: String,
    val seq: Long,
    val fromUid: String,
    val msgType: Int,
    val text: String,
    val sentAt: Long,
)
