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

@kotlin.PublishedApi
internal val json = Json { ignoreUnknownKeys = true }

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

    suspend inline fun <reified T> request(method: String, path: String, body: String?, token: String? = null): T {
        val text = rawRequest(method, path, body, token)
        return json.decodeFromString(text)
    }

    @kotlin.PublishedApi
    internal suspend fun rawRequest(method: String, path: String, body: String?, token: String?): String {
        val resp = Http.execute(
            method = method,
            url = "$baseUrl$path",
            body = body,
            token = token,
        )
        if (resp.first >= 400) throw ApiException("HTTP ${resp.first}: ${resp.second}")
        return resp.second
    }

    // ============ 二期：雁书号 / 通讯录 / 朋友圈 ===========

    /** 通讯录相关 */
    suspend fun requestContact(token: String, toUid: String, message: String): String {
        val resp = request<Map<String, String>>(
            "POST", "/v1/contacts/request",
            json.encodeToString(mapOf("to_uid" to toUid, "message" to message)), token,
        )
        return resp["status"] ?: "pending"
    }

    suspend fun acceptContact(token: String, requestId: String = "") {
        request<Map<String, Boolean>>("POST", "/v1/contacts/$requestId/accept", "{}", token)
    }

    suspend fun rejectContact(token: String, requestId: String = "") {
        request<Map<String, Boolean>>("POST", "/v1/contacts/$requestId/reject", "{}", token)
    }

    suspend fun contacts(token: String): List<ContactResp> =
        request("GET", "/v1/contacts", null, token)

    suspend fun contactRequests(token: String): List<ContactRequestResp> =
        request("GET", "/v1/contacts/requests", null, token)

    suspend fun searchByYid(token: String, yid: String): SearchUserResp =
        request("GET", "/v1/users/search?yid=$yid", null, token)

    suspend fun changeYid(token: String, newId: String) {
        request<Map<String, String>>("PUT", "/v1/me/yid", "{\"yid\":\"$newId\"}", token)
    }

    /** 朋友圈相关 */
    suspend fun createMoment(token: String, text: String, images: List<String>): String {
        val imgs = images.joinToString(",") { "\"$it\"" }
        val resp = request<Map<String, String>>(
            "POST", "/v1/moments", "{\"text\":\"$text\",\"images\":[$imgs]}", token,
        )
        return resp["id"] ?: ""
    }

    suspend fun momentFeed(token: String, beforeId: String = ""): List<MomentResp> =
        request("GET", "/v1/moments/feed" + if (beforeId.isEmpty()) "" else "?before_id=$beforeId", null, token)

    suspend fun myMoments(token: String, beforeId: String = ""): List<MomentResp> =
        request("GET", "/v1/moments/mine" + if (beforeId.isEmpty()) "" else "?before_id=$beforeId", null, token)

    suspend fun likeMoment(token: String, id: String) {
        request<Map<String, Boolean>>("POST", "/v1/moments/$id/like", "{}", token)
    }

    suspend fun unlikeMoment(token: String, id: String) {
        request<Map<String, Boolean>>("DELETE", "/v1/moments/$id/like", null, token)
    }

    suspend fun commentMoment(token: String, id: String, text: String) {
        val body = json.encodeToString(mapOf("text" to text))
        request<Map<String, String>>("POST", "/v1/moments/$id/comment", body, token)
    }

    suspend fun deleteMoment(token: String, id: String) {
        request<Map<String, Boolean>>("DELETE", "/v1/moments/$id", null, token)
    }

    /** 修改昵称 */
    suspend fun setRemark(token: String, targetUid: String, remark: String) {
        request<Map<String, Boolean>>("PUT", "/v1/contacts/$targetUid/remark",
            json.encodeToString(mapOf("remark" to remark)), token)
    }

    suspend fun removeContact(token: String, targetUid: String) {
        request<Map<String, Boolean>>("DELETE", "/v1/contacts/$targetUid", null, token)
    }

    suspend fun searchMessages(token: String, q: String): List<SearchHitResp> =
        request("GET", "/v1/search?q=${urlEncode(q)}", null, token)

    suspend fun setAvatar(token: String, key: String) {
        request<Map<String, Boolean>>("PUT", "/v1/me/avatar",
            json.encodeToString(mapOf("key" to key)), token)
    }

    suspend fun groupInfo(token: String, convId: String): GroupInfoResp =
        request("GET", "/v1/groups/$convId/info", null, token)

    suspend fun conversationMembers(token: String, convId: String): List<GroupMemberResp> =
        request("GET", "/v1/conversations/$convId/members", null, token)

    suspend fun addGroupMembers(token: String, convId: String, members: List<String>) {
        val ms = members.joinToString(",") { json.encodeToString(it) }
        val body = json.encodeToString(mapOf("members" to members))
        request<Map<String, Boolean>>("POST", "/v1/conversations/$convId/members", body, token)
    }

    suspend fun setAnnouncement(token: String, convId: String, announcement: String) {
        request<Map<String, Boolean>>("PUT", "/v1/groups/$convId/announcement",
            json.encodeToString(mapOf("announcement" to announcement)), token)
    }

    suspend fun kickMember(token: String, convId: String, uid: String) {
        request<Map<String, Boolean>>("DELETE", "/v1/groups/$convId/members/$uid", null, token)
    }

    suspend fun setGroupRole(token: String, convId: String, uid: String, role: String) {
        request<Map<String, Boolean>>("PUT", "/v1/groups/$convId/roles",
            json.encodeToString(mapOf("uid" to uid, "role" to role)), token)
    }

    suspend fun updateNickname(token: String, nickname: String) {
        val body = json.encodeToString(mapOf("nickname" to nickname))
        request<Map<String, Boolean>>("PUT", "/v1/me", body, token)
    }

    suspend fun me(token: String): MeResp =
        request("GET", "/v1/me", null, token)
}

class ApiException(message: String) : Exception(message)

@Serializable
data class SearchUserResp(val uid: String, val yid: String, val nickname: String)

@Serializable
data class ContactResp(val uid: String, val nickname: String, val yid: String, val remark: String, val avatar: String = "")

@Serializable
data class ContactRequestResp(
    val id: String, val from_uid: String, val message: String, val status: String,
    val created_at: Long, val nickname: String, val yid: String,
)

@Serializable
data class MomentCommentVO(val id: String, val uid: String, val nickname: String, val text: String, val created_at: Long)

@Serializable
data class MomentResp(
    val id: String, val uid: String, val nickname: String, val text: String,
    val images: List<String>? = null, val created_at: Long,
    val likes: Int = 0, val liked_by_me: Boolean = false,
    val comments: List<MomentCommentVO> = emptyList(),
)


@Serializable
data class MeResp(
    val uid: String, val username: String, val nickname: String,
    val avatar: String = "", val yid: String, val yid_changed: Boolean = true,
)

@Serializable
data class SearchHitResp(
    val server_msg_id: String, val conversation_id: String, val seq: Long,
    val from_uid: String, val text: String, val sent_at: Long,
)

@Serializable
data class GroupInfoResp(val id: String, val name: String, val owner_uid: String, val announcement: String = "")

@Serializable
data class GroupMemberResp(val uid: String, val nickname: String)

@Serializable
data class ConversationHistoryMsg(
    val serverMsgId: String,
    val seq: Long,
    val fromUid: String,
    val msgType: Int,
    val text: String,
    val sentAt: Long,
)


/** 极简 URL 编码（跨平台）：空格及非安全字符转 %XX */
internal fun urlEncode(s: String): String {
    val sb = StringBuilder()
    for (b in s.encodeToByteArray()) {
        val c = b.toInt()
        if (c in '0'.code..'9'.code || c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code ||
            c == '-'.code || c == '_'.code || c == '.'.code || c == '~'.code
        ) sb.append(c.toChar())
        else sb.append('%').append(("%02X".format(c)))
    }
    return sb.toString()
}
