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

/** 媒体面（LiveKit）连接状态，与信令状态分开：信令通了不代表声音通了 */
enum class CallMediaState {
    None,         // 还没进通话
    Connecting,   // 正在入会 / 申请麦克风
    Connected,    // 已入会，麦克风已打开
    Failed,       // 入会失败（权限被拒、连不上、地址没配…）
    Unsupported,  // 该平台没有媒体实现
}

data class CallUiState(
    val state: CallState = CallState.Idle,
    val callId: String = "",
    val peerUid: String = "",
    val roomName: String = "",
    val livekitToken: String = "",
    val video: Boolean = true,
    val media: CallMediaState = CallMediaState.None,
    val mediaError: String = "",
    val muted: Boolean = false,
)

/**
 * 通话控制器：封装信令状态机；媒体面通过 onConnected 回调交给各平台 WebRTC 实现。
 */
class CallController(
    val connection: ImConnection,
    internal var myUid: String,
    private val scope: kotlinx.coroutines.CoroutineScope = kotlinx.coroutines.CoroutineScope(SupervisorJob()),
    /** 媒体面实现：Web=livekit-client，桌面/Android 暂不支持 */
    private val media: im.client.call.MediaSession = im.client.call.createMediaSession(),
) {
    private val _state = MutableStateFlow(CallUiState())
    val uiState: StateFlow<CallUiState> = _state

    /** LiveKit SFU 地址（来自 site-config 的 livekit_url），登录后由界面层赋值 */
    var livekitUrl: String = ""

    /** 当前平台是否支持通话媒体（UI 据此提示，而不是假装在通话） */
    val mediaSupported: Boolean get() = media.supported

    /** 保留给外部的观察钩子（统计/提示用）；媒体接入已由 media 接管 */
    var onSessionReady: suspend (roomName: String, token: String) -> Unit = { _, _ -> }
    var onCallEnded: suspend () -> Unit = {}

    private var pollJob: Job? = null

    init {
        // 媒体层在通话中途坏掉（侧车崩溃 / 网络彻底断）：如实置为失败，
        // 不要停在「已接通」让人对着无声界面。
        media.setOnBroken { reason ->
            val cur = _state.value
            if (cur.state == CallState.InCall) {
                _state.value = cur.copy(
                    media = CallMediaState.Failed,
                    mediaError = reason.ifBlank { "通话媒体层已断开" },
                )
            }
        }
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
        teardownMedia()
        _state.value = CallUiState()
    }

    /** 静音 / 取消静音（只在通话中有效） */
    fun toggleMute() {
        val s = _state.value
        if (s.state != CallState.InCall) return
        val next = !s.muted
        media.setMuted(next)
        _state.value = s.copy(muted = next)
    }

    /**
     * 释放媒体资源：离开房间、关掉麦克风。
     * 所有「通话结束」的路径都必须经过这里，否则麦克风指示灯会一直亮着。
     */
    private fun teardownMedia() {
        scope.launch { runCatching { media.leave() } }
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
                // 平台不支持 / 地址没配：如实告知，不要停在"看起来在通话"的状态
                if (!media.supported) {
                    _state.value = prev.copy(
                        state = CallState.InCall,
                        roomName = sig.roomName,
                        livekitToken = sig.livekitToken,
                        media = CallMediaState.Unsupported,
                        mediaError = "该平台暂不支持通话（仅 Web 端可用）",
                    )
                    return
                }
                if (livekitUrl.isBlank()) {
                    _state.value = prev.copy(
                        state = CallState.InCall,
                        roomName = sig.roomName,
                        livekitToken = sig.livekitToken,
                        media = CallMediaState.Failed,
                        mediaError = "服务端未配置通话地址（livekit_url）",
                    )
                    return
                }
                _state.value = prev.copy(
                    state = CallState.InCall,
                    roomName = sig.roomName,
                    livekitToken = sig.livekitToken,
                    media = CallMediaState.Connecting,
                    mediaError = "",
                )
                onSessionReady(sig.roomName, sig.livekitToken)
                scope.launch {
                    val r = media.join(livekitUrl, sig.livekitToken)
                    val cur = _state.value
                    if (cur.state != CallState.InCall) return@launch  // 期间已挂断
                    _state.value = cur.copy(
                        media = if (r.isSuccess) CallMediaState.Connected else CallMediaState.Failed,
                        mediaError = r.exceptionOrNull()?.message ?: "",
                    )
                }
            }
            CallEventType.Reject -> {
                onCallEnded()
                teardownMedia()
                _state.value = CallUiState()
            }
            CallEventType.Cancel -> {
                onCallEnded()
                teardownMedia()
                _state.value = CallUiState()
            }
            CallEventType.Hangup, CallEventType.Timeout, CallEventType.Busy -> {
                onCallEnded()
                teardownMedia()
                _state.value = CallUiState()
            }
        }
    }

    private fun genCallId(): String =
        (0 until 12).joinToString("") { "0123456789abcdef"[Random.nextInt(16)].toString() }
}
