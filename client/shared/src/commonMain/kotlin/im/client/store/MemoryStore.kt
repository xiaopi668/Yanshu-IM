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

// 单个会话常驻内存的消息条数上限（超出丢最旧的已确认消息，DB 里仍全量保留）
private const val MAX_IN_MEMORY_MESSAGES = 500

// conversation_cache 里的保留行 id：记录缓存归属账号（不改表结构，换账号时按它判断是否清库）
private const val OWNER_ROW = "__cache_owner__"

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

    /** 本地缓存归属的账号 uid（来自保留行，跨启动有效；空串表示未知） */
    var cacheOwnerUid: String = ""
        private set

    fun setMyUid(uid: String) { myUidFlow.value = uid }

    init {
        // 启动时从 SQLite 加载缓存（Web 平台无 db，纯内存）
        val database = db
        if (database != null) {
            database.imQueries.loadAllConversations().executeAsList().forEach { c ->
                // 保留行不是会话，只用来识别缓存归属账号
                if (c.id == OWNER_ROW) { cacheOwnerUid = c.title; return@forEach }
                _conversations.value = _conversations.value + Conversation(c.id, c.type, c.title, c.last_seq, c.read_seq)
                _maxSeqs.value = _maxSeqs.value + (c.id to c.last_seq)
                // SQL 是 seq DESC 取最新 50 条，这里翻回升序供 UI 直接渲染
                val recent = database.imQueries.loadRecent(c.id, 50).executeAsList().asReversed()
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
                            failed = r.pending == 2L,
                        )
                    })
                }
            }
        }
    }

    /** 清空本地缓存（换账号登录时）：message_cache + conversation_cache 与内存一并清掉（Web 端 db=null 只清内存） */
    fun clearCaches() {
        db?.imQueries?.clearAllMessages()
        db?.imQueries?.clearAllConversations()
        _conversations.value = emptyList()
        _messages.value = emptyMap()
        _maxSeqs.value = emptyMap()
        cacheOwnerUid = ""
    }

    /** 记录缓存归属账号；换账号时先 clearCaches 再调用（保留行随 conversation_cache 一起写入） */
    fun setCacheOwner(uid: String) {
        cacheOwnerUid = uid
        db?.imQueries?.upsertConversation(OWNER_ROW, "meta", uid, 0, 0)
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
            msg.sentAt, msg.clientMsgId, when {
                msg.failed -> 2L
                msg.sending -> 1L
                else -> 0L
            },
        )
        val cid = msg.conversationId
        val current = _messages.value[cid] ?: emptyList()
        // 同一逻辑消息原位更新，否则按 seq 插入有序位置（不再整表 filter+sort）
        val idx = if (msg.serverMsgId.isNotEmpty()) {
            current.indexOfFirst { it.serverMsgId == msg.serverMsgId && !it.sending }
        } else {
            current.indexOfFirst { it.clientMsgId != null && it.clientMsgId == msg.clientMsgId }
        }
        val next = if (idx >= 0) {
            current.toMutableList().also { it[idx] = msg }
        } else {
            insertSorted(current, msg)
        }
        _messages.value = _messages.value + (cid to trimMessages(next))
        // 更新会话 lastSeq
        updateConversation(cid) { it.copy(lastSeq = maxOf(it.lastSeq, msg.seq)) }
        bumpMaxSeq(cid, msg.seq)
    }

    /**
     * 发送成功确认：保留消息条目，只把 pending 置 0、回填 serverMsgId/seq。
     * 不能直接删除：notify 回显可能不带或晚到，删了消息就从 UI 上"闪没"。
     */
    fun confirmMessage(clientMsgId: String, serverMsgId: String, seq: Long, conversationId: String) {
        db?.imQueries?.let { queries ->
            val pending = queries.loadPending(clientMsgId).executeAsList().firstOrNull()
            if (pending != null && serverMsgId.isNotEmpty()) {
                // pending 行（主键 p_xxx）回填为服务端行；同 server_msg_id 的回显行先到也一并覆盖，不留脏行
                queries.deletePending(clientMsgId)
                queries.upsertMessage(
                    serverMsgId, conversationId, seq, pending.from_uid, pending.msg_type,
                    pending.text, pending.attachment, pending.sent_at, clientMsgId, 0L,
                )
            } else {
                queries.markConfirmed(clientMsgId)
            }
        }
        val cid = conversationId
        val current = _messages.value[cid] ?: emptyList()
        // 按 client_msg_id 认领，不要求当前是"发送中"：
        // ACK 晚于发送超时到达时消息已被标成失败态，这里必须能把它纠正回已确认
        val idx = current.indexOfFirst { it.clientMsgId == clientMsgId }
        if (idx < 0) return
        val confirmed = current[idx].copy(serverMsgId = serverMsgId, seq = seq, sending = false, failed = false)
        // 回显行先到时会多出同 serverMsgId 的行，这里合并成一条并放回 seq 有序位置（ACK 没带 serverMsgId 就不去重）
        val rest = current.filterIndexed { i, m -> i != idx && (serverMsgId.isEmpty() || m.serverMsgId != serverMsgId) }
        _messages.value = _messages.value + (cid to insertSorted(rest, confirmed))
    }

    /** 按 client_msg_id 定位消息（UI 与重发逻辑只有这个 id，没有会话上下文） */
    fun messageByClientId(clientMsgId: String): Msg? {
        _messages.value.values.forEach { list ->
            list.firstOrNull { it.clientMsgId == clientMsgId }?.let { return it }
        }
        return null
    }

    /** 还没被服务端确认的消息（发送中 + 失败）：重连后据此自动重发 */
    fun unsentMessages(): List<Msg> = _messages.value.values.flatten().filter { it.sending || it.failed }

    /** 上行失败 / ACK 超时：置失败态，UI 给「点击重试」入口 */
    fun markSendFailed(clientMsgId: String) = setPendingState(clientMsgId, 2L, sending = false, failed = true)

    /** 重发前：回到「发送中」 */
    fun markSendPending(clientMsgId: String) = setPendingState(clientMsgId, 1L, sending = true, failed = false)

    private fun setPendingState(clientMsgId: String, dbState: Long, sending: Boolean, failed: Boolean) {
        db?.imQueries?.setPendingState(dbState, clientMsgId)
        var changed = false
        val next = _messages.value.mapValues { (_, list) ->
            val i = list.indexOfFirst { it.clientMsgId == clientMsgId }
            if (i < 0) list else {
                changed = true
                list.toMutableList().also { it[i] = it[i].copy(sending = sending, failed = failed) }
            }
        }
        if (changed) _messages.value = next
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

    /** 按 (seq, sentAt) 升序插入到正确位置，保持有序且不整表排序 */
    private fun insertSorted(list: List<Msg>, msg: Msg): List<Msg> {
        val pos = list.indexOfFirst { it.seq > msg.seq || (it.seq == msg.seq && it.sentAt > msg.sentAt) }
        return if (pos < 0) list + msg else list.subList(0, pos) + msg + list.subList(pos, list.size)
    }

    /** 单会话常驻内存上限：超出从最旧的已确认消息开始丢，发送中的保留 */
    private fun trimMessages(list: List<Msg>): List<Msg> {
        if (list.size <= MAX_IN_MEMORY_MESSAGES) return list
        var drop = list.size - MAX_IN_MEMORY_MESSAGES
        return list.filter { m ->
            when {
                m.sending -> true
                drop > 0 -> { drop--; false }
                else -> true
            }
        }
    }
}
