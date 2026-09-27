package im.client

import im.client.proto.FrameKind
import im.client.proto.Frames
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob

// 补拉翻页上限：has_more 一直为 true 时最多再拉 20 页，防止死循环/阻塞太久
private const val MAX_PULL_PAGES = 20

/**
 * ImClient 的接线扩展：ACK/pullResp → store。
 * 拆出来避免 ImClient 里出现循环依赖初始化问题。
 */
fun ImClient.wireCallbacks(scope: CoroutineScope = CoroutineScope(SupervisorJob())) {
    val store = storeInternal()
    // 各会话连续翻页计数（单 collector 内使用）
    val pulledPages = mutableMapOf<String, Int>()

    connection.acks.onEach { ack ->
        // ACK 到达：保留消息条目，只清 pending 并回填 serverMsgId/seq（回显未到时消息不丢）
        store.confirmMessage(ack.clientMsgId, ack.serverMsgId, ack.seq, ack.conversationId)
    }.launchIn(scope)

    connection.pullResps.onEach { resp ->
        store.setMaxSeqs(mapOf(resp.conversationId to resp.maxSeq))
        resp.msgs.forEach { store.upsertMessage(it) }
        // has_more：以本次收到的最后一条 seq 为游标继续拉下一页
        if (resp.hasMore && resp.msgs.isNotEmpty()) {
            val pages = (pulledPages[resp.conversationId] ?: 0) + 1
            if (pages <= MAX_PULL_PAGES) {
                pulledPages[resp.conversationId] = pages
                connection.pull(resp.conversationId, resp.msgs.maxOf { it.seq })
            } else {
                pulledPages.remove(resp.conversationId)
            }
        } else {
            pulledPages.remove(resp.conversationId)
        }
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
