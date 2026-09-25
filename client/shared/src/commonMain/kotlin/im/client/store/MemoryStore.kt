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

/** M2 骨架先用内存缓存；SQLDelight 持久化在 M2.5 接入 */
class MemoryStore {
    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations

    private val _messages = MutableStateFlow<Map<String, List<Msg>>>(emptyMap())
    val messages: StateFlow<Map<String, List<Msg>>> = _messages

    private val _maxSeqs = MutableStateFlow<Map<String, Long>>(emptyMap())
    val maxSeqs: StateFlow<Map<String, Long>> = _maxSeqs

    private val myUidFlow = MutableStateFlow("")
    val myUid: StateFlow<String> = myUidFlow

    fun setMyUid(uid: String) { myUidFlow.value = uid }

    fun setConversations(list: List<Conversation>) {
        _conversations.value = list.sortedWith(
            compareByDescending<Conversation> { it.lastSeq }.thenBy { it.id }
        )
        val newMax = _maxSeqs.value.toMutableMap()
        list.forEach { if ((newMax[it.id] ?: 0) < it.lastSeq) newMax[it.id] = it.lastSeq }
        _maxSeqs.value = newMax
    }

    fun upsertMessage(msg: Msg) {
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
        val cid = conversationId
        val current = _messages.value[cid] ?: emptyList()
        val next = current.filter { it.clientMsgId != clientMsgId || !it.sending }
        _messages.value = _messages.value + (cid to next)
    }

    fun removePending(clientMsgId: String, conversationId: String) {
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
