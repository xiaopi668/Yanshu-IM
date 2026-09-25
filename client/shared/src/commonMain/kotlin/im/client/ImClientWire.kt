package im.client

import im.client.proto.FrameKind
import im.client.proto.Frames
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

/**
 * ImClient 的接线扩展：ACK/pullResp → store。
 * 拆出来避免 ImClient 里出现循环依赖初始化问题。
 */
fun ImClient.wireCallbacks(scope: CoroutineScope = CoroutineScope(SupervisorJob())) {
    val store = storeInternal()

    connection.acks.onEach { ack ->
        // ACK 到达：pending 消息已被 notify 回显覆盖，这里只清 pending 标记
        store.confirmMessage(ack.clientMsgId, ack.serverMsgId, ack.seq, ack.conversationId)
    }.launchIn(scope)

    connection.pullResps.onEach { resp ->
        store.setMaxSeqs(mapOf(resp.conversationId to resp.maxSeq))
        resp.msgs.forEach { store.upsertMessage(it) }
    }.launchIn(scope)

    connection.authResults.onEach { r ->
        if (r.ok) {
            store.setMaxSeqs(r.maxSeqs)
            // 重连后按 seq 概览拉补（离线期间漏掉的消息）
            r.maxSeqs.forEach { (cid, maxSeq) ->
                val local = store.conversations.value.firstOrNull { it.id == cid }
                val read = local?.readSeq ?: 0
                if (maxSeq > read) connection.pull(cid, read)
            }
        }
    }.launchIn(scope)
}
