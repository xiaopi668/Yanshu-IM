package im.client

import im.client.api.Api
import im.client.net.ConnState
import im.client.net.ImConnection
import im.client.proto.Msg
import im.client.proto.MsgType
import im.client.store.Conversation
import im.client.store.MemoryStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlin.random.Random

/**
 * 客户端门面：登录 → 连接 → 消息同步（seq 拉补）→ 存储。
 * UI 层只依赖它。
 */
class ImClient(
    val apiBaseUrl: String,
    val gatewayWsUrl: String,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob()),
) {
    val api = Api(apiBaseUrl)
    private val store = MemoryStore(im.client.db.createDriver()?.let { im.client.db.ImDatabase(it) })
    val connection = ImConnection(gatewayWsUrl)
    val callController = CallController(connection, "")

    var myUid: String = ""
        private set
    var myToken: String = ""
        private set
    var myNickname: String = ""
        private set

    /** 改完昵称后同步本地缓存，避免要重新登录侧栏才更新 */
    fun setNickname(nickname: String) {
        myNickname = nickname
    }

    val conversations = store.conversations
    val messages = store.messages
    val connectionState = connection.state

    private val _sessionInvalid = MutableStateFlow<String?>(null)
    /**
     * 非空表示服务端拒绝鉴权（token 失效 / 账号被封 / 令牌被撤销）。
     * UI 观察到后应清本地会话并回到登录页 —— 否则用户只会看到一直"连接中"。
     */
    val sessionInvalid: StateFlow<String?> = _sessionInvalid

    /** 鉴权被拒：停掉连接与重连，并把原因交给 UI */
    internal fun onAuthRejected(reason: String) {
        _sessionInvalid.value = reason
        connection.disconnect()
    }

    private var syncJob: Job? = null

    /** 未确认消息的超时计时器：clientMsgId → job */
    private val sendTimeouts = mutableMapOf<String, Job>()

    suspend fun register(
        username: String, password: String, nickname: String,
        yid: String = "", email: String = "", emailCode: String = "", turnstileToken: String = "",
    ): Pair<String, String> {
        val resp = api.register(username, password, nickname, yid, email, emailCode, turnstileToken)
        return resp.uid to resp.token
    }

    /** OIDC 等外部流程拿到的 token 直接登录 */
    suspend fun loginWithToken(token: String): String {
        myToken = token
        val me = api.me(token)
        myUid = me.uid
        myNickname = me.nickname
        bindCacheOwner(me.uid)
        store.setMyUid(me.uid)
        callController.myUid = me.uid
        return me.uid
    }

    suspend fun login(username: String, password: String, turnstileToken: String = ""): Pair<String, String> {
        val resp = api.login(username, password, platformOf(), turnstileToken)
        myUid = resp.uid
        myToken = resp.token
        bindCacheOwner(resp.uid)
        store.setMyUid(resp.uid)
        callController.myUid = resp.uid
        myNickname = api.me(resp.token).nickname
        return resp.uid to resp.token
    }

    /**
     * 登录成功后绑定本地缓存归属账号：与缓存归属不一致（换账号/首次）就清空本地缓存，
     * 避免读到上一个账号的 message_cache / conversation_cache（Web 端 db=null，内部安全跳过）。
     */
    private fun bindCacheOwner(uid: String) {
        if (store.cacheOwnerUid != uid) {
            store.clearCaches()
            store.setCacheOwner(uid)
        }
    }

    suspend fun startSession() {
        connection.connect(myToken)
        // 拉会话列表 + 每个会话拉补
        refreshConversations()
        syncJob?.cancel()
        syncJob = connection.notifies.onEach { msg ->
            store.upsertMessage(msg)
            connection.markRead(msg.conversationId, msg.seq)
            store.markRead(msg.conversationId, msg.seq)
        }.launchIn(scope)
    }

    fun stop() {
        syncJob?.cancel()
        connection.disconnect()
    }

    /**
     * 令牌变更通知：改密码 / 退出其它设备后服务端会作废旧令牌并下发新令牌，
     * 界面层据此更新本地保存的会话（否则下次启动会拿着已撤销的令牌）。
     */
    var onTokenRefreshed: ((String) -> Unit)? = null

    /**
     * 换用新令牌并重连。
     *
     * 必须重连：网关建连时记录了令牌版本，版本递增后旧连接会被服务端踢掉；
     * 只改内存里的字符串而不重连，界面会表现为「刚改完密码就掉线」。
     */
    private suspend fun adoptToken(newToken: String) {
        if (newToken.isEmpty()) return
        myToken = newToken
        connection.disconnect()
        connection.connect(newToken)
        onTokenRefreshed?.invoke(newToken)
    }

    /** 修改密码（成功后其它设备下线，当前设备自动换新令牌） */
    suspend fun changePassword(oldPassword: String, newPassword: String) {
        adoptToken(api.changePassword(myToken, oldPassword, newPassword))
    }

    /** 退出其它设备（当前设备保留） */
    suspend fun logoutOtherDevices() {
        adoptToken(api.logoutOtherDevices(myToken))
    }

    /** 绑定 / 修改邮箱 */
    suspend fun updateEmail(email: String, code: String = "") {
        api.setEmail(myToken, email, code)
    }

    suspend fun refreshConversations() {
        val list = api.conversations(myToken)
        store.setConversations(list.map { Conversation(it.id, it.type, it.title, it.last_seq, it.read_seq) })
        list.forEach { conv ->
            // 关键：按「本地已有最大 seq」判断缺多少，而不是按「已读位置」。
            // 用 read_seq 的话，一个会话只要没有未读消息就不会拉补 ——
            // 刷新页面后本地缓存是空的，聊天记录就整段消失（服务端搜索却还能搜到）。
            val localMax = store.maxSeqs.value[conv.id] ?: 0L
            if (conv.last_seq > localMax) {
                connection.pull(conv.id, localMax)
            }
        }
    }

    /**
     * 加载某个会话的历史（分页由 ImClientWire 的 has_more 续拉负责）。
     *
     * 之前这个方法没有任何调用点 —— 刷新后只有"有未读"的会话才会拉消息，
     * 已读完的会话是一段空白。现在由聊天页在进入时显式调用。
     */
    suspend fun loadHistory(conversationId: String) {
        val conv = store.conversations.value.firstOrNull { it.id == conversationId } ?: return
        if (conv.lastSeq <= 0) return
        val localMax = store.maxSeqs.value[conversationId] ?: 0L
        connection.pull(conversationId, localMax)
    }

    suspend fun sendMessage(conversationId: String, text: String, type: MsgType = MsgType.Text): Msg {
        val clientMsgId = newClientMsgId()
        val pending = Msg(
            conversationId = conversationId,
            fromUid = myUid,
            msgType = type,
            text = text,
            sentAt = currentTimeMillis(),
            clientMsgId = clientMsgId,
            sending = true,
        )
        store.upsertMessage(pending)
        transmit(pending)
        return pending
    }

    private fun newClientMsgId() = "cm-" + Random.nextLong(0, Long.MAX_VALUE).toString(36)

    /**
     * 上行发送。失败**不抛给 UI**，而是落成失败态：
     * 用户能看到「发送失败，点击重试」，重连时也会自动重发。
     * 重发沿用同一个 client_msg_id，服务端按它幂等去重，不会产生重复消息。
     */
    private suspend fun transmit(msg: Msg) {
        val clientMsgId = msg.clientMsgId ?: return
        armSendTimeout(clientMsgId)
        try {
            val att = msg.attachment
            if (att != null) {
                connection.sendAttachment(msg.conversationId, clientMsgId, msg.msgType, msg.text, att)
            } else {
                connection.sendText(msg.conversationId, clientMsgId, msg.text, msg.msgType)
            }
        } catch (e: Throwable) {
            // 未连接 / 写失败：立刻置失败态，不等超时
            clearSendTimeout(clientMsgId)
            store.markSendFailed(clientMsgId)
        }
    }

    /** ACK 超时兜底：服务端与网络都没回音时，也要让用户看到失败并能重发 */
    private fun armSendTimeout(clientMsgId: String) {
        sendTimeouts.remove(clientMsgId)?.cancel()
        sendTimeouts[clientMsgId] = scope.launch {
            delay(SEND_TIMEOUT_MS)
            val m = store.messageByClientId(clientMsgId) ?: return@launch
            if (m.sending) store.markSendFailed(clientMsgId)
        }
    }

    /** 收到 ACK / 失败应答后取消超时计时；由 ImClientWire 调用 */
    internal fun clearSendTimeout(clientMsgId: String) {
        sendTimeouts.remove(clientMsgId)?.cancel()
    }

    /** 重发一条失败或仍在发送中的消息（沿用原 client_msg_id） */
    fun resend(clientMsgId: String) {
        val msg = store.messageByClientId(clientMsgId) ?: return
        if (!msg.sending && !msg.failed) return
        store.markSendPending(clientMsgId)
        scope.launch { transmit(msg.copy(sending = true, failed = false)) }
    }

    /** 重连成功后把所有未确认消息重发一遍（幂等键保证不会重复） */
    internal fun flushOutbox() {
        store.unsentMessages().forEach { m -> m.clientMsgId?.let { resend(it) } }
    }

    /**
     * 发送文件/图片：pickFile → 预签名直传 → 发 Attachment 消息。
     * kind 由 mime 推断。
     */
    suspend fun sendAttachment(conversationId: String, file: im.client.file.PickedFile): Msg {
        val kind = when {
            file.mime.startsWith("image/") -> "image"
            file.mime.startsWith("audio/") -> "audio"
            file.mime.startsWith("video/") -> "video"
            else -> "file"
        }
        val key = api.uploadAttachment(myToken, kind, file.bytes)
        val clientMsgId = newClientMsgId()
        val type = when (kind) {
            "image" -> MsgType.Image
            "audio" -> MsgType.Audio
            "video" -> MsgType.Video
            else -> MsgType.File
        }
        val pending = Msg(
            conversationId = conversationId,
            fromUid = myUid,
            msgType = type,
            text = file.name,
            attachment = im.client.proto.Attachment(
                // 这里放对象 key，不放带凭证的地址：消息会被广播给会话成员并落库
                url = key,
                name = file.name,
                size = file.bytes.size.toLong(),
                mime = file.mime,
            ),
            sentAt = currentTimeMillis(),
            clientMsgId = clientMsgId,
            sending = true,
        )
        store.upsertMessage(pending)
        transmit(pending)
        return pending
    }

    fun conversationFor(peerUid: String): Conversation? =
        store.conversations.value.firstOrNull { it.id.contains(peerUid) && it.type == "single" }

    suspend fun openSingle(peerUid: String): String {
        val existing = conversationFor(peerUid)
        if (existing != null) return existing.id
        return api.createSingle(myToken, peerUid)
    }

    fun storeInternal(): MemoryStore = store

    companion object {
        /** 发送超时：超过这个时间没收到 ACK/失败帧就标失败，让用户能重发 */
        private const val SEND_TIMEOUT_MS = 10_000L
    }
}

/** 平台标识（M4 音视频时也用它区分能力） */
expect fun platformOf(): String

expect fun currentTimeMillis(): Long
