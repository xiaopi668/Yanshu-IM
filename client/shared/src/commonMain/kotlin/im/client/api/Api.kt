package im.client.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

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

    // turnstileToken 必须能传进来：服务端 /v1/login 在站点开启人机验证时会校验它
    // （main.go 的 checkTurnstile 对登录和注册都做了检查）。
    // 之前这里没有这个参数，客户端永远发不出令牌 —— 只要站点开了人机验证，
    // 登录必然被服务端拒绝，而且界面上看不出任何原因。
    suspend fun login(
        username: String,
        password: String,
        platform: String,
        turnstileToken: String = "",
        /** 账号开启两步验证时必填；服务端会在响应里用 totp_required 提示 */
        totpCode: String = "",
    ): LoginResp {
        val body = json.encodeToString(
            mapOf(
                "username" to username,
                "password" to password,
                "platform" to platform,
                "turnstile_token" to turnstileToken,
                "totp_code" to totpCode,
            )
        )
        return request("POST", "/v1/login", body)
    }

    // ---------- 两步验证（TOTP） ----------

    suspend fun totpStatus(token: String): TotpStatusResp =
        request("GET", "/v1/me/totp", null, token)

    suspend fun totpSetup(token: String): TotpSetupResp =
        request("POST", "/v1/me/totp/setup", "{}", token)

    /** 校验一次验证码并正式开启；返回一次性恢复码（只此一次） */
    suspend fun totpEnable(token: String, code: String): List<String> {
        val body = json.encodeToString(mapOf("code" to code))
        val resp = request<TotpEnableResp>("POST", "/v1/me/totp/enable", body, token)
        return resp.recovery_codes
    }

    // ---------- 通行密钥（WebAuthn） ----------

    /**
     * 绑定：开始注册仪式，返回服务端原样的 options JSON（含 publicKey）。
     * 调用方把它交给平台层 navigator.credentials.create。
     */
    suspend fun passkeyBegin(token: String): String =
        rawRequest("POST", "/v1/me/passkey/begin", "{}", token)

    /**
     * 绑定：提交浏览器产出的 credential JSON。
     * 注意是**原样 JSON 作为请求体**（服务端用 WebAuthn 库解析），不做二次包装。
     */
    suspend fun passkeyFinish(token: String, credentialJson: String, label: String = ""): Boolean {
        rawRequest(
            "POST",
            "/v1/me/passkey/finish?label=" + urlEncode(label),
            credentialJson,
            token,
        )
        return true
    }

    suspend fun passkeyList(token: String): List<PasskeyInfo> =
        request("GET", "/v1/me/passkey", null, token)

    suspend fun passkeyDelete(token: String, id: String) {
        request<Map<String, Boolean>>("DELETE", "/v1/me/passkey/" + urlEncode(id), null, token)
    }

    /** 登录：开始无用户名（discoverable）断言，返回 {options, session_id} */
    suspend fun passkeyLoginBegin(): PasskeyLoginBeginResp =
        request("POST", "/v1/passkey/login/begin", "{}", null)

    /** 登录：提交断言 JSON，换取令牌 */
    suspend fun passkeyLoginFinish(sessionId: String, credentialJson: String): LoginResp =
        request(
            "POST",
            "/v1/passkey/login/finish?session_id=" + urlEncode(sessionId),
            credentialJson,
            null,
        )

    /** 关闭两步验证：需要当前验证码或一个恢复码 */
    suspend fun totpDisable(token: String, code: String) {
        val body = json.encodeToString(mapOf("code" to code))
        request<Map<String, Boolean>>("POST", "/v1/me/totp/disable", body, token)
    }

    suspend fun friends(token: String): List<FriendResp> =
        request("GET", "/v1/friends", null, token)

    suspend fun createSingle(token: String, peerUid: String): String {
        val resp = request<Map<String, String>>("POST", "/v1/conversations/single", "{\"peer_uid\":\"$peerUid\"}", token)
        return resp["id"]!!
    }

    suspend fun createGroup(token: String, name: String, members: List<String>): String {
        // 群名是用户输入，同样不能手拼（见 createMoment 的说明）
        val body = buildJsonObject {
            put("name", name)
            put("members", buildJsonArray { members.forEach { add(JsonPrimitive(it)) } })
        }.toString()
        val resp = request<Map<String, String>>("POST", "/v1/conversations/group", body, token)
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
        val body = json.encodeToString(mapOf("kind" to kind))
        val cred = request<Map<String, String>>("POST", "/v1/upload-token", body, token)
        val putUrl = cred["put_url"] ?: throw RuntimeException("服务端未返回上传地址")
        try {
            Http.putBinary(putUrl, bytes)
        } catch (e: Throwable) {
            // 这个失败最常见的原因是「对象存储地址浏览器访问不到」：
            // 部署时 IM_STORAGE_ENDPOINT 填了内网主机名（如 minio:9000），
            // 预签名 URL 就指向那个地址，浏览器解析不了；
            // 其次是桶没配 CORS，浏览器会被跨域策略拦掉。
            // 不把地址带出来的话，用户只看到「上传失败」，根本无从排查。
            val host = putUrl.substringAfter("://", putUrl).substringBefore("/")
            throw RuntimeException(
                "附件上传失败：无法访问对象存储 $host。" +
                    "请确认 IM_STORAGE_ENDPOINT 是浏览器可达的地址（不要用容器内主机名），" +
                    "并给存储桶配置允许本站来源的 CORS",
                e,
            )
        }
        return cred["key"] ?: throw RuntimeException("服务端未返回对象 key")
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
        request("GET", "/v1/users/search?yid=" + urlEncode(yid), null, token)

    suspend fun changeYid(token: String, newId: String) {
        request<Map<String, String>>("PUT", "/v1/me/yid", "{\"yid\":\"$newId\"}", token)
    }

    /**
     * 修改密码。服务端会递增令牌版本让**其它设备**立即失效，
     * 并返回一个新令牌给当前设备（否则改完自己也掉线）。
     */
    suspend fun changePassword(token: String, oldPassword: String, newPassword: String): String {
        val body = "{\"old_password\":\"" + jsonEscape(oldPassword) +
            "\",\"new_password\":\"" + jsonEscape(newPassword) + "\"}"
        val resp = request<Map<String, String>>("POST", "/v1/me/password", body, token)
        return resp["token"] ?: ""
    }

    /**
     * 忘记密码：用邮箱验证码重置（不需要登录）。
     * 重置成功后服务端会递增令牌版本，所有设备下线。
     */
    suspend fun resetPassword(email: String, code: String, newPassword: String) {
        val body = "{\"email\":\"" + jsonEscape(email) +
            "\",\"code\":\"" + jsonEscape(code) +
            "\",\"new_password\":\"" + jsonEscape(newPassword) + "\"}"
        request<Map<String, String>>("POST", "/v1/password/reset", body, null)
    }

    /** 让其它设备下线；当前设备返回新令牌 */
    suspend fun logoutOtherDevices(token: String): String {
        val resp = request<Map<String, String>>("POST", "/v1/me/logout-others", "{}", token)
        return resp["token"] ?: ""
    }

    /** 绑定 / 修改邮箱（站点开启邮箱验证码时需要 code） */
    suspend fun setEmail(token: String, email: String, code: String = "") {
        val body = "{\"email\":\"" + jsonEscape(email) + "\",\"code\":\"" + jsonEscape(code) + "\"}"
        request<Map<String, String>>("PUT", "/v1/me/email", body, token)
    }

    /** 朋友圈相关 */
    suspend fun createMoment(token: String, text: String, images: List<String>): String {
        // 以前是手拼 JSON：正文里只要有换行或引号，拼出来的就是非法 JSON，
        // 服务端直接回 "invalid character '\n' in string literal"（用户看到的就是这句话）。
        // 改用结构化构造，换行/引号/反斜杠都由序列化器处理。
        val body = buildJsonObject {
            put("text", text)
            put("images", buildJsonArray { images.forEach { add(JsonPrimitive(it)) } })
        }.toString()
        val resp = request<Map<String, String>>("POST", "/v1/moments", body, token)
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

/**
 * 服务端是否因为「缺第二因子」而拒绝了这次登录。
 *
 * 用 JSON 解析而不是 body.contains("totp_required")：后者会被错误信息里的同名字样骗到，
 * 也会在服务端调整字段顺序/空白时失效。
 */
fun ApiException.isTotpRequired(): Boolean = runCatching {
    val obj = Json.parseToJsonElement(body).jsonObject
    obj["totp_required"]?.jsonPrimitive?.booleanOrNull ?: false
}.getOrDefault(false)

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
data class PasskeyInfo(
    val id: String = "",
    val label: String = "",
    val created_at: Long = 0,
    val last_used_at: Long = 0,
)

@Serializable
data class PasskeyLoginBeginResp(
    /** 服务端原样的断言 options（含 publicKey），直接交给平台层 */
    val options: kotlinx.serialization.json.JsonElement? = null,
    val session_id: String = "",
)

@Serializable
data class TotpStatusResp(val enabled: Boolean = false, val recovery_left: Int = 0)

@Serializable
data class TotpSetupResp(
    val secret: String = "",
    val otpauth_url: String = "",
    /** 二维码矩阵：每行一串 '0'/'1'（服务端生成，已含静区） */
    val qr_rows: List<String> = emptyList(),
    val qr_size: Int = 0,
)

@Serializable
data class TotpEnableResp(val recovery_codes: List<String> = emptyList())

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

/**
 * 最小 JSON 字符串转义。
 *
 * 现有请求体都是手拼的 JSON，密码里一旦出现引号或反斜杠就会拼出非法 JSON
 * （服务端只会回 400，排查起来很费劲）。这里统一转义。
 */
internal fun jsonEscape(s: String): String {
    val sb = StringBuilder(s.length + 8)
    for (c in s) {
        when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> if (c.code < 0x20) {
                sb.append("\\u").append(c.code.toString(16).padStart(4, '0'))
            } else {
                sb.append(c)
            }
        }
    }
    return sb.toString()
}
