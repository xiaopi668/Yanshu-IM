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

    val conversations = store.conversations
    val messages = store.messages
    val connectionState = connection.state

    private var syncJob: Job? = null

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
        store.setMyUid(me.uid)
        callController.myUid = me.uid
        return me.uid
    }

    suspend fun login(username: String, password: String): Pair<String, String> {
        val resp = api.login(username, password, platformOf())
        myUid = resp.uid
        myToken = resp.token
        store.setMyUid(resp.uid)
        callController.myUid = resp.uid
        myNickname = api.me(resp.token).nickname
        return resp.uid to resp.token
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

    suspend fun refreshConversations() {
        val list = api.conversations(myToken)
        store.setConversations(list.map { Conversation(it.id, it.type, it.title, it.last_seq, it.read_seq) })
        list.forEach { conv ->
            val pending = list.first { it.id == conv.id }
            if (pending.last_seq > pending.read_seq) {
                connection.pull(conv.id, pending.read_seq)
            }
        }
    }

    suspend fun loadHistory(conversationId: String) {
        val conv = store.conversations.value.firstOrNull { it.id == conversationId } ?: return
        if (conv.lastSeq > 0) {
            connection.pull(conversationId, 0)
        }
    }

    suspend fun sendMessage(conversationId: String, text: String, type: MsgType = MsgType.Text): Msg {
        val clientMsgId = "cm-" + Random.nextLong(0, Long.MAX_VALUE).toString(36)
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
        connection.sendText(conversationId, clientMsgId, text, type)
        return pending
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
        val clientMsgId = "cm-" + Random.nextLong(0, Long.MAX_VALUE).toString(36)
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
                url = api.downloadUrl(myToken, key),
                name = file.name,
                size = file.bytes.size.toLong(),
                mime = file.mime,
            ),
            sentAt = currentTimeMillis(),
            clientMsgId = clientMsgId,
            sending = true,
        )
        store.upsertMessage(pending)
        connection.sendAttachment(conversationId, clientMsgId, type, file.name, pending.attachment!!)
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
        init {
            // 把 ACK 转成 store 的 confirm 逻辑在 ImClientWire.kt 里接线
        }
    }
}

/** 平台标识（M4 音视频时也用它区分能力） */
expect fun platformOf(): String

expect fun currentTimeMillis(): Long
