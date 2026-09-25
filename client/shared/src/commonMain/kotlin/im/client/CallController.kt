package im.client

import im.client.net.ImConnection
import im.client.proto.CallEventType
import im.client.proto.CallSignalData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlin.random.Random

enum class CallState {
    Idle,        // 空闲
    RingingOut,  // 主叫响铃中
    RingingIn,   // 被叫响铃中
    InCall,      // 通话中（已拿 LiveKit token）
}

data class CallUiState(
    val state: CallState = CallState.Idle,
    val callId: String = "",
    val peerUid: String = "",
    val roomName: String = "",
    val livekitToken: String = "",
    val video: Boolean = true,
)

/**
 * 通话控制器：封装信令状态机；媒体面通过 onConnected 回调交给各平台 WebRTC 实现。
 */
class CallController(
    val connection: ImConnection,
    internal var myUid: String,
    private val scope: kotlinx.coroutines.CoroutineScope = kotlinx.coroutines.CoroutineScope(SupervisorJob()),
) {
    private val _state = MutableStateFlow(CallUiState())
    val uiState: StateFlow<CallUiState> = _state

    /** 媒体面接入回调（平台 actual 提供：Android=webrtc-android, Web=livekit-client JS） */
    var onSessionReady: suspend (roomName: String, token: String) -> Unit = { _, _ -> }
    var onCallEnded: suspend () -> Unit = {}

    private var pollJob: Job? = null

    init {
        connection.callSignals.onEach { sig: im.client.proto.CallSignalData -> scope.launch { handleSignal(sig) } }.launchIn(scope)
        // 响铃超时轮询（服务端 60s 清理，客户端也本地计时）
        pollJob = scope.launch {
            while (true) {
                delay(5_000)
                val s = _state.value
                if (s.state == CallState.RingingIn || s.state == CallState.RingingOut) {
                    // 服务端 60s 会发 timeout；本地无 token 则忽略，靠服务端 TIMEOUT 推送
                }
            }
        }
    }

    fun startCall(peerUid: String, video: Boolean = true) {
        val callId = genCallId()
        _state.value = CallUiState(state = CallState.RingingOut, callId = callId, peerUid = peerUid, video = video)
        scope.launch {
            connection.sendCallSignal(
                CallSignalData(callId = callId, event = CallEventType.Invite, text = peerUid)
            )
        }
        // 本地 65s 兜底超时
        scope.launch {
            delay(65_000)
            if (_state.value.callId == callId && _state.value.state == CallState.RingingOut) {
                cancelCall()
            }
        }
    }

    fun acceptCall() {
        val s = _state.value
        if (s.state != CallState.RingingIn) return
        scope.launch {
            connection.sendCallSignal(CallSignalData(callId = s.callId, event = CallEventType.Accept))
        }
    }

    fun rejectCall() {
        val s = _state.value
        if (s.state != CallState.RingingIn) return
        scope.launch {
            connection.sendCallSignal(CallSignalData(callId = s.callId, event = CallEventType.Reject))
        }
        _state.value = CallUiState()
    }

    fun cancelCall() {
        val s = _state.value
        if (s.state != CallState.RingingOut) return
        scope.launch {
            connection.sendCallSignal(CallSignalData(callId = s.callId, event = CallEventType.Cancel, text = s.peerUid))
        }
        _state.value = CallUiState()
    }

    fun hangUp() {
        val s = _state.value
        if (s.state == CallState.InCall) {
            scope.launch {
                connection.sendCallSignal(CallSignalData(callId = s.callId, event = CallEventType.Hangup))
                onCallEnded()
            }
        }
        _state.value = CallUiState()
    }

    private suspend fun handleSignal(sig: CallSignalData) {
        when (sig.event) {
            CallEventType.Invite -> {
                // 被叫：响铃
                _state.value = CallUiState(
                    state = CallState.RingingIn,
                    callId = sig.callId,
                    peerUid = sig.text,
                )
            }
            CallEventType.Accept -> {
                // 主叫/被叫都会收到（带各自的 token）
                val prev = _state.value
                _state.value = prev.copy(
                    state = CallState.InCall,
                    roomName = sig.roomName,
                    livekitToken = sig.livekitToken,
                )
                onSessionReady(sig.roomName, sig.livekitToken)
            }
            CallEventType.Reject -> {
                onCallEnded()
                _state.value = CallUiState()
            }
            CallEventType.Cancel -> {
                onCallEnded()
                _state.value = CallUiState()
            }
            CallEventType.Hangup, CallEventType.Timeout, CallEventType.Busy -> {
                onCallEnded()
                _state.value = CallUiState()
            }
        }
    }

    private fun genCallId(): String =
        (0 until 12).joinToString("") { "0123456789abcdef"[Random.nextInt(16)].toString() }
}
