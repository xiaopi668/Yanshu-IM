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
    suspend fun register(
        username: String, password: String, nickname: String,
        yid: String = "", email: String = "", emailCode: String = "", turnstileToken: String = "",
    ): LoginResp {
        val body = json.encodeToString(
            mapOf(
                "username" to username, "password" to password, "nickname" to nickname,
                "yid" to yid, "email" to email, "email_code" to emailCode, "turnstile_token" to turnstileToken,
            )
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

    /**
     * 申请附件的短时下载地址：票据由服务端签发、与单个对象 key 绑定、10 分钟过期。
     *
     * 注意不要把登录 token 拼进这个地址 —— 它会被写进消息体的 Attachment.url，
     * 随 MsgNotify 广播给会话全部成员并永久落库，等于把 7 天有效的账号凭证发给所有人。
     */
    suspend fun attachmentUrl(token: String, key: String): String =
        request<Map<String, String>>(
            "POST", "/v1/attachments/ticket", json.encodeToString(mapOf("key" to key)), token,
        )["url"] ?: ""

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
        if (resp.first >= 400) {
            throw ApiException(httpErrorMessage(resp.first, resp.second), resp.first, resp.second)
        }
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

    suspend fun siteConfig(): Map<String, kotlinx.serialization.json.JsonElement> =
        request("GET", "/v1/site-config", null, null)

    suspend fun sendEmailCode(email: String): Boolean {
        val body = json.encodeToString(mapOf("email" to email))
        return request<Map<String, Boolean>>("POST", "/v1/email/send-code", body, null)["ok"] ?: false
    }

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

    /** 昵称 + 用户名一起提交（服务端两者都传就都改，只传一个也允许） */
    suspend fun updateProfile(token: String, nickname: String, username: String) {
        val body = json.encodeToString(mapOf("nickname" to nickname, "username" to username))
        request<Map<String, Boolean>>("PUT", "/v1/me", body, token)
    }

    suspend fun me(token: String): MeResp =
        request("GET", "/v1/me", null, token)
}

/**
 * 接口调用失败。
 *
 * 之前客户端直接把服务端返回体拼进消息里（'HTTP 400: {"error":"..."}'），
 * 用户界面上就会看到一坨原始 JSON —— 那是给程序看的，不是给人看的。
 * 现在统一走 [httpErrorMessage] 提取可读文案，同时把状态码单独带出来，
 * 需要按状态判断的地方（例如令牌失效要回登录页）用 [status] 而不是在字符串里找数字。
 */
class ApiException(
    message: String,
    val status: Int = 0,
    val body: String = "",
) : Exception(message)

/** 服务端错误响应体的标准形态是 {"error":"..."}；个别接口用 {"message":"..."} */
@kotlinx.serialization.Serializable
private data class ApiErrorBody(val error: String = "", val message: String = "")

/**
 * 把「状态码 + 响应体」变成用户能读的一句话。
 * 优先取 JSON 里的 error / message；拿不到再退回纯文本（截断），最后按状态码给兜底文案。
 */
fun httpErrorMessage(code: Int, body: String): String {
    val text = body.trim()
    if (text.isNotEmpty()) {
        val fromJson = runCatching {
            val o = kotlinx.serialization.json.Json.parseToJsonElement(text)
            val e = kotlinx.serialization.json.Json.decodeFromJsonElement(ApiErrorBody.serializer(), o)
            e.error.ifBlank { e.message }
        }.getOrNull()
        if (!fromJson.isNullOrBlank()) return fromJson
        // 不是 JSON（或没有 error 字段）时，短文本可以直接用；HTML/超长内容不要往界面上丢
        if (!text.startsWith("{") && !text.startsWith("<") && text.length <= 200) return text
    }
    return when (code) {
        400 -> "请求参数有误"
        401 -> "登录已过期，请重新登录"
        403 -> "没有权限执行该操作"
        404 -> "请求的内容不存在"
        409 -> "操作冲突，请刷新后重试"
        429 -> "操作太频繁，请稍后再试"
        in 500..599 -> "服务端异常（HTTP $code），请稍后重试"
        else -> "请求失败（HTTP $code）"
    }
}

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
        else {
            sb.append('%')
            val hex = "0123456789ABCDEF"
            sb.append(hex[(c shr 4) and 0xF]).append(hex[c and 0xF])
        }
    }
    return sb.toString()
}


/** 无 token 的裸 HTTP 文本请求（登录前场景：site-config / 邮箱验证码 / OIDC） */
suspend fun rawHttpText(method: String, url: String, body: String? = null): String {
    val (code, text) = Http.execute(method, url, body, null)
    if (code >= 400) throw ApiException(httpErrorMessage(code, text), code, text)
    return text
}
