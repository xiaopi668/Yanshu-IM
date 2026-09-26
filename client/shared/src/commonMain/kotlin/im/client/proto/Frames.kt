package im.client.proto

// 与 proto/im.proto 一一对应的客户端侧消息编解码。

enum class MsgType(val v: Int) {
    Text(0), Image(1), File(2), Audio(3), Video(4), Call(5), System(6);
    companion object {
        fun from(v: Int) = entries.first { it.v == v }
    }
}

data class Attachment(
    val url: String = "",
    val name: String = "",
    val size: Long = 0,
    val mime: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val duration: Int = 0,
)

data class Msg(
    val serverMsgId: String = "",
    val conversationId: String = "",
    val seq: Long = 0,
    val fromUid: String = "",
    val msgType: MsgType = MsgType.Text,
    val text: String = "",
    val attachment: Attachment? = null,
    val sentAt: Long = 0,
    val mentionUids: List<String> = emptyList(),
    // 客户端侧状态
    val clientMsgId: String? = null,
    val sending: Boolean = false,
)

data class AuthRespData(val ok: Boolean, val reason: String?, val maxSeqs: Map<String, Long>)

data class PullRespData(val conversationId: String, val msgs: List<Msg>, val hasMore: Boolean, val maxSeq: Long)

// Frame 字段号：1 heartbeat, 2 auth_req, 3 auth_resp, 4 msg_send, 5 msg_ack, 6 msg_notify, 7 pull_req, 8 pull_resp, 9 msg_read, 10 call_signal

enum class CallEventType(val v: Int) {
    Invite(0), Accept(1), Reject(2), Cancel(3), Hangup(4), Timeout(5), Busy(6);
    companion object {
        fun from(v: Int) = entries.firstOrNull { it.v == v } ?: Busy
    }
}

data class CallSignalData(
    val callId: String = "",
    val event: CallEventType = CallEventType.Invite,
    val roomName: String = "",
    val livekitToken: String = "",
    val text: String = "",
)

object Frames {
    fun heartbeat(): ByteArray {
        val h = ProtoWriter() // Heartbeat 空消息
        val f = ProtoWriter()
        f.messageField(1, h)
        return f.toByteArray()
    }

    fun authReq(token: String, deviceId: String, platform: String): ByteArray {
        val a = ProtoWriter()
        a.stringField(1, token)
        a.stringField(2, deviceId)
        a.stringField(3, platform)
        val f = ProtoWriter()
        f.messageField(2, a)
        return f.toByteArray()
    }

    fun msgSend(clientMsgId: String, conversationId: String, msgType: MsgType, text: String, attachment: Attachment? = null): ByteArray {
        val m = ProtoWriter()
        m.stringField(1, clientMsgId)
        m.stringField(2, conversationId)
        m.varintField(3, msgType.v.toLong())
        m.stringField(4, text)
        attachment?.let { m.messageField(5, encodeAttachment(it)) }
        val f = ProtoWriter()
        f.messageField(4, m)
        return f.toByteArray()
    }

    fun msgSendAttachment(clientMsgId: String, conversationId: String, msgType: MsgType, name: String, attachment: Attachment): ByteArray {
        return msgSend(clientMsgId, conversationId, msgType, name, attachment)
    }

    fun pullReq(conversationId: String, afterSeq: Long, limit: Int): ByteArray {
        val p = ProtoWriter()
        p.stringField(1, conversationId)
        p.varintField(2, afterSeq)
        p.varintField(3, limit.toLong())
        val f = ProtoWriter()
        f.messageField(7, p)
        return f.toByteArray()
    }

    fun msgRead(conversationId: String, upToSeq: Long): ByteArray {
        val r = ProtoWriter()
        r.stringField(1, conversationId)
        r.varintField(2, upToSeq)
        val f = ProtoWriter()
        f.messageField(9, r)
        return f.toByteArray()
    }

    // ---------- decode ----------

    fun decodeFrame(bytes: ByteArray): FrameKind {
        val f = ProtoReader(bytes).readAll()
        if (f.containsKey(1)) return FrameKind.Heartbeat
        f[3]?.firstOrNull()?.bytes?.let { return FrameKind.AuthResp(decodeAuthResp(it)) }
        f[5]?.firstOrNull()?.bytes?.let { return FrameKind.MsgAck(decodeMsgAck(it)) }
        f[6]?.firstOrNull()?.bytes?.let { return FrameKind.MsgNotify(decodeMsg(it)) }
        f[8]?.firstOrNull()?.bytes?.let { return FrameKind.MsgPullResp(decodePullResp(it)) }
        f[11]?.firstOrNull()?.bytes?.let { return FrameKind.ContactEventFrame(decodeContactEvent(it)) }
        f[10]?.firstOrNull()?.bytes?.let { return FrameKind.CallSignalFrame(decodeCallSignal(it)) }
        return FrameKind.Unknown
    }

    fun decodeMsg(bytes: ByteArray): Msg {
        val m = ProtoReader(bytes).readAll()
        return Msg(
            conversationId = m.firstString(1) ?: "",
            seq = m.firstVarint(2) ?: 0,
            serverMsgId = m.firstString(3) ?: "",
            fromUid = m.firstString(4) ?: "",
            msgType = MsgType.from((m.firstVarint(5) ?: 0).toInt()),
            text = m.firstString(6) ?: "",
            attachment = m.firstBytes(7)?.let { decodeAttachment(it) },
            sentAt = m.firstVarint(8) ?: 0,
            mentionUids = m.bytes(9)?.map { it.decodeToString() } ?: emptyList(),
        )
    }

    private fun decodeAuthResp(bytes: ByteArray): AuthRespData {
        val a = ProtoReader(bytes).readAll()
        val maxSeqs = a.bytes(3).mapNotNull { entry ->
            val e = ProtoReader(entry).readAll()
            val key = e.firstString(1) ?: return@mapNotNull null
            key to (e.firstVarint(2) ?: 0)
        }.toMap()
        return AuthRespData(ok = a.firstVarint(1) == 1L, reason = a.firstString(2), maxSeqs = maxSeqs.toMap())
    }

    private fun decodeMsgAck(bytes: ByteArray): MsgAckData {
        val a = ProtoReader(bytes).readAll()
        return MsgAckData(
            clientMsgId = a.firstString(1) ?: "",
            seq = a.firstVarint(2) ?: 0,
            serverMsgId = a.firstString(3) ?: "",
            conversationId = a.firstString(4) ?: "",
        )
    }

    private fun decodePullResp(bytes: ByteArray): PullRespData {
        val p = ProtoReader(bytes).readAll()
        return PullRespData(
            conversationId = p.firstString(3) ?: "",
            msgs = p.bytes(1).map { decodeMsg(it) },
            hasMore = p.firstVarint(2) == 1L,
            maxSeq = p.firstVarint(4) ?: 0,
        )
    }

    private fun decodeAttachment(bytes: ByteArray): Attachment {
        val a = ProtoReader(bytes).readAll()
        return Attachment(
            url = a.firstString(1) ?: "",
            name = a.firstString(2) ?: "",
            size = a.firstVarint(3) ?: 0,
            mime = a.firstString(4) ?: "",
            width = (a.firstVarint(5) ?: 0).toInt(),
            height = (a.firstVarint(6) ?: 0).toInt(),
            duration = (a.firstVarint(7) ?: 0).toInt(),
        )
    }

    private fun encodeAttachment(a: Attachment): ProtoWriter {
        val w = ProtoWriter()
        w.stringField(1, a.url)
        w.stringField(2, a.name)
        w.varintField(3, a.size)
        w.stringField(4, a.mime)
        w.varintField(5, a.width.toLong())
        w.varintField(6, a.height.toLong())
        w.varintField(7, a.duration.toLong())
        return w
    }
}

data class MsgAckData(val clientMsgId: String, val seq: Long, val serverMsgId: String, val conversationId: String)

sealed class FrameKind {
    object Heartbeat : FrameKind()
    data class AuthResp(val data: AuthRespData) : FrameKind()
    data class MsgAck(val data: MsgAckData) : FrameKind()
    data class MsgNotify(val msg: Msg) : FrameKind()
    data class MsgPullResp(val resp: PullRespData) : FrameKind()
    data class CallSignalFrame(val data: CallSignalData) : FrameKind()
    data class ContactEventFrame(val data: ContactEventData) : FrameKind()
    object Unknown : FrameKind()
}

fun callSignal(data: CallSignalData): ByteArray {
    val s = ProtoWriter()
    s.stringField(1, data.callId)
    s.varintField(2, data.event.v.toLong())
    s.stringField(3, data.roomName)
    s.stringField(4, data.livekitToken)
    s.stringField(5, data.text)
    val f = ProtoWriter()
    f.messageField(10, s)
    return f.toByteArray()
}

fun decodeCallSignal(bytes: ByteArray): CallSignalData {
    val s = ProtoReader(bytes).readAll()
    return CallSignalData(
        callId = s.firstString(1) ?: "",
        event = CallEventType.from((s.firstVarint(2) ?: 0).toInt()),
        roomName = s.firstString(3) ?: "",
        livekitToken = s.firstString(4) ?: "",
        text = s.firstString(5) ?: "",
    )
}



// ContactEvent（Frame field 11）：通讯录事件
data class ContactEventData(
    val type: String = "",       // request / accepted / rejected
    val requestId: String = "",
    val fromUid: String = "",
    val yid: String = "",
    val nickname: String = "",
    val message: String = "",
)

fun Frames.decodeContactEvent(bytes: ByteArray): ContactEventData {
    val s = ProtoReader(bytes).readAll()
    return ContactEventData(
        type = s.firstString(1) ?: "",
        requestId = s.firstString(2) ?: "",
        fromUid = s.firstString(3) ?: "",
        yid = s.firstString(4) ?: "",
        nickname = s.firstString(5) ?: "",
        message = s.firstString(6) ?: "",
    )
}
