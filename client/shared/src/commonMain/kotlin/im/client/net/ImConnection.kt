package im.client.net

import im.client.proto.AuthRespData
import im.client.proto.Frames
import im.client.proto.FrameKind
import im.client.proto.CallSignalData
import im.client.proto.callSignal
import im.client.proto.MsgAckData
import im.client.proto.Msg
import im.client.proto.MsgType
import im.client.proto.PullRespData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.random.Random

sealed class ConnState {
    data object Disconnected : ConnState()
    data object Connecting : ConnState()
    data object Authenticated : ConnState()
}

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

    private var token: String? = null
    private var deviceId: String = Random.nextLong().toString(16)
    private val platform = detectPlatform()
    private var ws: WsSocket? = null
    private var heartbeatJob: Job? = null
    private var reconnectAttempts = 0

    fun connect(token: String) {
        this.token = token
        scope.launch { openSession() }
    }

    fun disconnect() {
        token = null
        heartbeatJob?.cancel()
        ws?.close()
        _state.value = ConnState.Disconnected
    }

    suspend fun sendText(conversationId: String, clientMsgId: String, text: String, type: MsgType = MsgType.Text) {
        ws?.send(Frames.msgSend(clientMsgId, conversationId, type, text))
    }

    suspend fun sendAttachment(
        conversationId: String,
        clientMsgId: String,
        type: MsgType,
        name: String,
        attachment: im.client.proto.Attachment,
    ) {
        ws?.send(Frames.msgSendAttachment(clientMsgId, conversationId, type, name, attachment))
    }

    suspend fun pull(conversationId: String, afterSeq: Long, limit: Int = 200) {
        ws?.send(Frames.pullReq(conversationId, afterSeq, limit))
    }

    suspend fun markRead(conversationId: String, upToSeq: Long) {
        ws?.send(Frames.msgRead(conversationId, upToSeq))
    }

    /** 通话信令上行 */
    suspend fun sendCallSignal(data: im.client.proto.CallSignalData) {
        ws?.send(callSignal(data))
    }

    suspend fun heartbeat() {
        ws?.send(Frames.heartbeat())
    }

    private suspend fun openSession() {
        val tok = token ?: return
        _state.value = ConnState.Connecting
        try {
            val socket = WsSocket(gatewayWsUrl, scope)
            ws = socket
            // open 挂起直到握手完成；成功后立刻发鉴权帧
            val ok = socket.open { frame ->
                when (val kind = Frames.decodeFrame(frame)) {
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
                    else -> {}
                }
            }
            if (!ok) {
                _state.value = ConnState.Disconnected
                scheduleReconnect()
                return
            }
            socket.send(Frames.authReq(tok, deviceId, platform))
            // 读循环在 open 的协程里持续运行；这里阻塞会破坏心跳调度，改为等待断开
        } catch (e: Throwable) {
            _state.value = ConnState.Disconnected
            scheduleReconnect()
        }
    }

    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (true) {
                delay(20_000)
                ws?.send(Frames.heartbeat())
            }
        }
    }

    private fun scheduleReconnect() {
        if (token == null) return
        if (++reconnectAttempts > 6) return
        scope.launch {
            delay((1L shl reconnectAttempts) * 1_000)
            openSession()
        }
    }
}

private fun detectPlatform(): String = "kmp"
