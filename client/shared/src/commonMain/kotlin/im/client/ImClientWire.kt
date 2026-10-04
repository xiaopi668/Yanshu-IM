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
        clearSendTimeout(ack.clientMsgId)
        store.confirmMessage(ack.clientMsgId, ack.serverMsgId, ack.seq, ack.conversationId)
    }.launchIn(scope)

    connection.errors.onEach { err ->
        // 服务端明确拒绝（不是成员 / 落库失败 / 不支持）：立刻置失败态。
        // 没有这条通路时，失败只写在服务端日志里，客户端会一直停在"发送中"。
        if (err.refClientMsgId.isNotEmpty()) {
            clearSendTimeout(err.refClientMsgId)
            store.markSendFailed(err.refClientMsgId)
        }
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
            // 重连成功：把断线期间没发出去 / 没被确认的消息补发一遍。
            // 服务端按 client_msg_id 幂等，重发不会产生重复消息。
            flushOutbox()
        } else {
            // 鉴权被拒（token 失效 / 被封禁 / 令牌被管理员撤销）：
            // 这是终态，重连没有意义，交给 UI 清会话回登录页
            onAuthRejected(r.reason ?: "unauthorized")
        }
    }.launchIn(scope)
}
