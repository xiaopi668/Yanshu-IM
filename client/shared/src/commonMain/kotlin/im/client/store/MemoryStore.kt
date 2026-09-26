package im.client.store

import im.client.proto.Msg
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class Conversation(
    val id: String,
    val type: String,       // single / group
    val title: String,
    val lastSeq: Long = 0,
    val readSeq: Long = 0,
)

/** 内存缓存层 + SQLite 持久化（Web 平台 db=null，降级为纯内存） */
class MemoryStore(private val db: im.client.db.ImDatabase? = null) {
    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations

    private val _messages = MutableStateFlow<Map<String, List<Msg>>>(emptyMap())
    val messages: StateFlow<Map<String, List<Msg>>> = _messages

    private val _maxSeqs = MutableStateFlow<Map<String, Long>>(emptyMap())
    val maxSeqs: StateFlow<Map<String, Long>> = _maxSeqs

    private val myUidFlow = MutableStateFlow("")
    val myUid: StateFlow<String> = myUidFlow

    fun setMyUid(uid: String) { myUidFlow.value = uid }

    init {
        // 启动时从 SQLite 加载缓存（Web 平台无 db，纯内存）
        val database = db
        if (database != null) {
            database.imQueries.loadAllConversations().executeAsList().forEach { c ->
                _conversations.value = _conversations.value + Conversation(c.id, c.type, c.title, c.last_seq, c.read_seq)
                _maxSeqs.value = _maxSeqs.value + (c.id to c.last_seq)
                val recent = database.imQueries.loadRecent(c.id, 50).executeAsList()
                if (recent.isNotEmpty()) {
                    _messages.value = _messages.value + (c.id to recent.map { r ->
                        Msg(
                            serverMsgId = r.server_msg_id,
                            conversationId = r.conversation_id,
                            seq = r.seq,
                            fromUid = r.from_uid,
                            msgType = im.client.proto.MsgType.from(r.msg_type.toInt()),
                            text = r.text ?: "",
                            attachment = r.attachment?.let { runCatching { kotlinx.serialization.json.Json.decodeFromString<im.client.proto.Attachment>(it) }.getOrNull() },
                            sentAt = r.sent_at,
                            clientMsgId = r.client_msg_id,
                            sending = r.pending == 1L,
                        )
                    })
                }
            }
        }
    }

    /** 清空本地缓存（换账号登录时） */
    fun clearCaches() {
        db?.imQueries?.clearConversation("")
        _conversations.value = emptyList()
        _messages.value = emptyMap()
        _maxSeqs.value = emptyMap()
    }

    fun setConversations(list: List<Conversation>) {
        db?.imQueries?.let { queries ->
            list.forEach { c -> queries.upsertConversation(c.id, c.type, c.title, c.lastSeq, c.readSeq) }
        }
        _conversations.value = list.sortedWith(
            compareByDescending<Conversation> { it.lastSeq }.thenBy { it.id }
        )
        val newMax = _maxSeqs.value.toMutableMap()
        list.forEach { if ((newMax[it.id] ?: 0) < it.lastSeq) newMax[it.id] = it.lastSeq }
        _maxSeqs.value = newMax
    }

    fun upsertMessage(msg: Msg) {
        db?.imQueries?.upsertMessage(
            msg.serverMsgId.ifEmpty { "p_" + (msg.clientMsgId ?: msg.sentAt.toString()) },
            msg.conversationId, msg.seq, msg.fromUid, msg.msgType.v.toLong(), msg.text,
            msg.attachment?.let { runCatching { kotlinx.serialization.json.Json.encodeToString(it) }.getOrNull() },
            msg.sentAt, msg.clientMsgId, if (msg.sending) 1L else 0L,
        )
        val cid = msg.conversationId
        val current = _messages.value[cid] ?: emptyList()
        // 按 server_msg_id 去重后追加，按 seq 排序
        val next = current
            .filter { it.serverMsgId != msg.serverMsgId || it.sending }
            .plus(msg)
            .sortedWith(compareBy({ it.seq }, { it.sentAt }))
        _messages.value = _messages.value + (cid to next)
        // 更新会话 lastSeq
        updateConversation(cid) { it.copy(lastSeq = maxOf(it.lastSeq, msg.seq)) }
        bumpMaxSeq(cid, msg.seq)
    }

    /** 发送成功确认：把 pending 消息标记为已确认 */
    fun confirmMessage(clientMsgId: String, serverMsgId: String, seq: Long, conversationId: String) {
        db?.imQueries?.markConfirmed(clientMsgId)
        val cid = conversationId
        val current = _messages.value[cid] ?: emptyList()
        val next = current.filter { it.clientMsgId != clientMsgId || !it.sending }
        _messages.value = _messages.value + (cid to next)
    }

    fun removePending(clientMsgId: String, conversationId: String) {
        db?.imQueries?.deletePending(clientMsgId)
        val cid = conversationId
        val current = _messages.value[cid] ?: emptyList()
        _messages.value = _messages.value + (cid to current.filter { it.clientMsgId != clientMsgId })
    }

    fun markRead(cid: String, upTo: Long) {
        updateConversation(cid) { it.copy(readSeq = maxOf(it.readSeq, upTo)) }
    }

    fun bumpMaxSeq(cid: String, seq: Long) {
        val cur = _maxSeqs.value
        if ((cur[cid] ?: 0) < seq) _maxSeqs.value = cur + (cid to seq)
    }

    fun setMaxSeqs(m: Map<String, Long>) {
        val cur = _maxSeqs.value
        _maxSeqs.value = m.entries.associate { it.key to maxOf(it.value, cur[it.key] ?: 0) }
    }

    private fun updateConversation(cid: String, transform: (Conversation) -> Conversation) {
        _conversations.value = _conversations.value.map {
            if (it.id == cid) transform(it) else it
        }
    }
}
