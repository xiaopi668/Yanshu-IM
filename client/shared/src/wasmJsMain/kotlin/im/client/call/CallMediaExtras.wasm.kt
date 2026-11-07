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

    actual fun setSpeaker(on: Boolean) = Unit
    actual fun toggleSpeaker() = Unit
    actual fun openAppSettings(): Boolean = false
}
