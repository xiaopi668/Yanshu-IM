package im.client.call

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 桌面端没有媒体面（AGENT.md §1「不做」：JVM 没有官方 Kotlin SDK），
 * 因此这里全部是常量：supported=false，UI 不会显示免提按钮，也不会走进通话态。
 */
actual object CallMediaExtras {
    private val falseFlow = MutableStateFlow(false)
    private val emptyText = MutableStateFlow("")

    actual val speakerOn: StateFlow<Boolean> = falseFlow.asStateFlow()
    actual val speakerSupported: Boolean = false
    actual val reconnecting: StateFlow<Boolean> = falseFlow.asStateFlow()
    actual val micPermissionBlocked: StateFlow<Boolean> = falseFlow.asStateFlow()
    actual val videoBlocked: StateFlow<Boolean> = falseFlow.asStateFlow()
    actual fun setVideoBlocked(blocked: Boolean) = Unit

    // 摄像头翻转/镜像：侧车只做音频，这里如实 false（docs/AGENT-camera-switch-mirror.md §6.7）。
    // 不为了让按钮「看起来能点」而返回 true —— 那会变成一个点了没反应的按钮。
    actual val cameraSwitchSupported: Boolean = false
    actual val mirrorOn: StateFlow<Boolean> = falseFlow.asStateFlow()
    actual val cameraSwitchHint: StateFlow<String> = emptyText.asStateFlow()

    actual fun setSpeaker(on: Boolean) = Unit
    actual fun toggleSpeaker() = Unit
    actual fun openAppSettings(): Boolean = false
}
