package im.client.call

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Web 端的音频路由由浏览器/操作系统决定（livekit-client 自己不提供听筒切换），
 * 麦克风授权失败也只会体现在 `media.join` 的失败原因里 —— 浏览器不会给出
 * 「永久拒绝」这个可判定的状态，所以这里没有「去设置」入口。
 */
actual object CallMediaExtras {
    private val falseFlow = MutableStateFlow(false)

    actual val speakerOn: StateFlow<Boolean> = falseFlow.asStateFlow()
    actual val speakerSupported: Boolean = false
    actual val reconnecting: StateFlow<Boolean> = falseFlow.asStateFlow()
    actual val micPermissionBlocked: StateFlow<Boolean> = falseFlow.asStateFlow()
    actual val videoBlocked: StateFlow<Boolean> = falseFlow.asStateFlow()
    actual fun setVideoBlocked(blocked: Boolean) = Unit

    // ---------- 摄像头翻转 / 本地镜像（docs/AGENT-camera-switch-mirror.md §4.3） ----------

    /**
     * 浏览器有 getUserMedia + `Room.getLocalDevices` + `switchActiveDevice`，能力是真的。
     * 单摄像头设备由 [cameraSwitchHint] 说明原因，而不是在这里返回 false ——
     * 那是运行时状态，不是平台能力。
     */
    actual val cameraSwitchSupported: Boolean = true

    private val _mirrorOn = MutableStateFlow(false)

    /** 本地预览是否镜像；由 [WebMediaSession] 按设备 label 猜出的前后摄与用户选择写入 */
    actual val mirrorOn: StateFlow<Boolean> = _mirrorOn.asStateFlow()

    private val _cameraSwitchHint = MutableStateFlow("")

    /** 「翻转」不可用的原因（只有一颗摄像头时会写上） */
    actual val cameraSwitchHint: StateFlow<String> = _cameraSwitchHint.asStateFlow()

    internal fun setMirrorOn(value: Boolean) {
        if (_mirrorOn.value != value) _mirrorOn.value = value
    }

    internal fun setCameraSwitchHint(reason: String) {
        if (_cameraSwitchHint.value != reason) _cameraSwitchHint.value = reason
    }

    actual fun setSpeaker(on: Boolean) = Unit
    actual fun toggleSpeaker() = Unit
    actual fun openAppSettings(): Boolean = false
}
