package im.client.net

import im.client.platformOf
import im.client.proto.AuthRespData
import im.client.proto.Frames
import im.client.proto.FrameKind
import im.client.proto.CallSignalData
import im.client.proto.callSignal
import im.client.proto.MsgAckData
import im.client.proto.Msg
import im.client.proto.MsgType
import im.client.proto.PullRespData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

sealed class ConnState {
    data object Disconnected : ConnState()
    data object Connecting : ConnState()
    data object Authenticated : ConnState()
}

/**
 * 连接不可用（未连接/正在重连）。
 * 单独定义成异常类型，是为了让发送方能明确区分"没发出去"和"发出去了但服务端拒绝"，
 * 前者可以自动重发，后者要变成失败态让用户决定。
 */
class NotConnectedException : Exception("connection not available")

class ImConnection(private val gatewayWsUrl: String) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _state = MutableStateFlow<ConnState>(ConnState.Disconnected)
    val state: StateFlow<ConnState> = _state

    private val _authResults = MutableSharedFlow<AuthRespData>(extraBufferCapacity = 8)
    val authResults: SharedFlow<AuthRespData> = _authResults

    private val _notifies = MutableSharedFlow<Msg>(extraBufferCapacity = 1024)
    val notifies: SharedFlow<Msg> = _notifies

    private val _acks = MutableSharedFlow<MsgAckData>(extraBufferCapacity = 256)
    val acks: SharedFlow<MsgAckData> = _acks

    private val _pullResps = MutableSharedFlow<PullRespData>(extraBufferCapacity = 64)
    val pullResps: SharedFlow<PullRespData> = _pullResps

    private val _callSignals = MutableSharedFlow<CallSignalData>(extraBufferCapacity = 64)
    val callSignals: SharedFlow<CallSignalData> = _callSignals

    private val _contactEvents = MutableSharedFlow<im.client.proto.ContactEventData>(extraBufferCapacity = 64)
    val contactEvents: SharedFlow<im.client.proto.ContactEventData> = _contactEvents

    /** 服务端失败应答（Frame field 12）：失败必须可见，不能只写服务端日志 */
    private val _errors = MutableSharedFlow<im.client.proto.ErrorData>(extraBufferCapacity = 64)
    val errors: SharedFlow<im.client.proto.ErrorData> = _errors

    private var token: String? = null
    private var deviceId: String = Random.nextLong().toString(16)
    private val platform = platformOf()
    private var ws: WsSocket? = null
    /** 当前会话任务（建连 → 等待断开 → 重连），disconnect() 时取消它 */
    private var sessionJob: Job? = null
    private var heartbeatJob: Job? = null
    private var reconnectAttempts = 0
    /** 心跳回包计数：读循环里 +1，心跳协程观察是否超时（StateFlow 跨线程安全） */
    private val pongCount = MutableStateFlow(0L)

    fun connect(token: String) {
        this.token = token
        reconnectAttempts = 0
        sessionJob?.cancel()
        sessionJob = scope.launch { openSession() }
    }

    fun disconnect() {
        token = null                  // 主动断开：openSession 不再重连
        sessionJob?.cancel()
        heartbeatJob?.cancel()
        ws?.close()
        ws = null
        _state.value = ConnState.Disconnected
    }

    suspend fun sendText(conversationId: String, clientMsgId: String, text: String, type: MsgType = MsgType.Text) {
        send(Frames.msgSend(clientMsgId, conversationId, type, text))
    }

    suspend fun sendAttachment(
        conversationId: String,
        clientMsgId: String,
        type: MsgType,
        name: String,
        attachment: im.client.proto.Attachment,
    ) {
        send(Frames.msgSendAttachment(clientMsgId, conversationId, type, name, attachment))
    }

    suspend fun pull(conversationId: String, afterSeq: Long, limit: Int = 200) {
        // 控制帧失败不上抛：这里的调用方是 SharedFlow 的收集协程，
        // 抛出去会把收集器整个打死（之后再也收不到任何帧）。断线重连后会重新拉补。
        runCatching { send(Frames.pullReq(conversationId, afterSeq, limit)) }
    }

    suspend fun markRead(conversationId: String, upToSeq: Long) {
        runCatching { send(Frames.msgRead(conversationId, upToSeq)) }
    }

    /**
     * 通话信令上行。未连接/写失败时不上抛：
     * 调用方（CallController）是一堆裸 scope.launch，抛出去会变成未捕获异常
     * （Android 上会崩进程）。信令本身的失败已有本地兜底（65s 响铃超时、本地状态机）。
     */
    suspend fun sendCallSignal(data: im.client.proto.CallSignalData) {
        runCatching { send(callSignal(data)) }
    }

    suspend fun heartbeat() {
        runCatching { send(Frames.heartbeat()) }
    }

    /**
     * 上行发送。未连接时抛 [NotConnectedException]（而不是静默返回）：
     * 静默返回会让调用方以为已经发出去了，消息永远停在"发送中"且不会被重发。
     */
    private suspend fun send(bytes: ByteArray) {
        val socket = ws ?: throw NotConnectedException()
        try {
            socket.send(bytes)
        } catch (e: CancellationException) {
            throw e                 // 调用方协程被取消，不算发送失败
        } catch (e: Throwable) {
            socket.close()
            throw e
        }
    }

    private suspend fun openSession() {
        val tok = token ?: return
        _state.value = ConnState.Connecting
        val socket = WsSocket(gatewayWsUrl, scope)
        ws = socket
        try {
            // open 挂起直到握手完成；成功后立刻发鉴权帧
            val ok = socket.open { frame -> handleFrame(frame) }
            if (ok) {
                socket.send(Frames.authReq(tok, deviceId, platform))
                // 挂起直到读循环退出：服务端断开 / 心跳超时 / 发送失败都会回到这里
                socket.awaitClosed()
            }
        } catch (e: CancellationException) {
            throw e                            // disconnect() 主动取消：不重连
        } catch (e: Throwable) {
            // 建连/读写异常：当作会话断开
        } finally {
            heartbeatJob?.cancel()
            heartbeatJob = null
            ws = null
            socket.close()
        }
        // 会话真正断开：状态回 Disconnected，再按指数退避重连（token=null 时停手）
        _state.value = ConnState.Disconnected
        scheduleReconnect()
    }

    private fun handleFrame(frame: ByteArray) {
        when (val kind = Frames.decodeFrame(frame)) {
            // 解码失败的脏帧直接丢弃
            null -> {}
            FrameKind.Heartbeat -> pongCount.value = pongCount.value + 1   // 心跳回包即 pong
            is FrameKind.AuthResp -> {
                if (kind.data.ok) {
                    _state.value = ConnState.Authenticated
                    reconnectAttempts = 0
                    startHeartbeat()
                }
                _authResults.tryEmit(kind.data)
            }
            is FrameKind.MsgNotify -> _notifies.tryEmit(kind.msg)
            is FrameKind.MsgAck -> _acks.tryEmit(kind.data)
            is FrameKind.MsgPullResp -> _pullResps.tryEmit(kind.resp)
            is FrameKind.CallSignalFrame -> _callSignals.tryEmit(kind.data)
            is FrameKind.ContactEventFrame -> _contactEvents.tryEmit(kind.data)
            is FrameKind.ErrorFrame -> _errors.tryEmit(kind.data)
            else -> {}
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (true) {
                delay(PING_PERIOD)
                val before = pongCount.value
                try {
                    ws?.send(Frames.heartbeat())
                } catch (e: Throwable) {
                    ws?.close()   // 发送失败：断开会话，触发重连
                    return@launch
                }
                // pong 超时：判定死连接，主动关闭触发重连
                if (withTimeoutOrNull(PONG_WAIT) { pongCount.first { it > before } } == null) {
                    ws?.close()
                    return@launch
                }
            }
        }
    }

    private fun scheduleReconnect() {
        if (token == null) return                     // 用户主动退出/切换账号
        if (++reconnectAttempts > MAX_RECONNECT) return   // 指数退避最多重连 6 次
        sessionJob = scope.launch {
            delay((1L shl reconnectAttempts) * 1_000)
            openSession()
        }
    }

    private companion object {
        /** 心跳周期 */
        const val PING_PERIOD = 20_000L
        /** 发 ping 后等 pong 的超时，超过即认为连接已死 */
        const val PONG_WAIT = 10_000L
        /** 断线重连最大次数 */
        const val MAX_RECONNECT = 6
    }
}
