package im.client.call

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Android 端的通话附加能力：免提、重连、麦克风权限落地。
 *
 * 三条实现约定（见 commonMain 的同名 expect）：
 * - 未接通时 [setSpeaker]/[toggleSpeaker] 安全忽略（转给 [CallAudioRouter]，它自己判 active）
 * - [reconnecting] 只反映**接通之后**的重连；入会阶段的失败走 `CallMediaState.Failed`
 * - 所有 StateFlow 的写入都在主线程（MediaSession 事件回调、Router 都在主线程）
 *
 * 写入方法标了 `internal`：只给 [AndroidMediaSession]/[CallAudioRouter] 用，界面层读不到，
 * 免得 UI 直接改「免提已开」造成状态和硬件不一致。
 */
actual object CallMediaExtras {
    private val _speakerOn = MutableStateFlow(false)
    actual val speakerOn: StateFlow<Boolean> = _speakerOn.asStateFlow()

    /** Android 有 AudioManager，能真正切听筒/外放 */
    actual val speakerSupported: Boolean = true

    private val _reconnecting = MutableStateFlow(false)
    actual val reconnecting: StateFlow<Boolean> = _reconnecting.asStateFlow()

    private val _micPermissionBlocked = MutableStateFlow(false)
    actual val micPermissionBlocked: StateFlow<Boolean> = _micPermissionBlocked.asStateFlow()

    private val _videoBlocked = MutableStateFlow(false)
    actual val videoBlocked: StateFlow<Boolean> = _videoBlocked.asStateFlow()

    actual fun setVideoBlocked(blocked: Boolean) {
        _videoBlocked.value = blocked
    }

    // ---------- 摄像头翻转 / 本地镜像（docs/AGENT-camera-switch-mirror.md §4.3） ----------

    /** Android 用 SurfaceViewRenderer 的 GL 镜像 + LocalVideoTrack.switchCamera，能力是真的 */
    actual val cameraSwitchSupported: Boolean = true

    private val _mirrorOn = MutableStateFlow(false)

    /** 本地预览是否镜像；真实值由 [AndroidMediaSession] 按摄像头朝向与用户选择写入 */
    actual val mirrorOn: StateFlow<Boolean> = _mirrorOn.asStateFlow()

    private val _cameraSwitchHint = MutableStateFlow("")

    /** 「翻转」不可用的原因（只有一颗摄像头时会写上） */
    actual val cameraSwitchHint: StateFlow<String> = _cameraSwitchHint.asStateFlow()

    /** MediaSession：镜像状态回写（界面只读，不直接改，免得和渲染器实际状态不一致） */
    internal fun setMirrorOn(value: Boolean) {
        if (_mirrorOn.value != value) _mirrorOn.value = value
    }

    /** MediaSession：「翻转」不可用的原因（空串 = 可用） */
    internal fun setCameraSwitchHint(reason: String) {
        if (_cameraSwitchHint.value != reason) _cameraSwitchHint.value = reason
    }

    actual fun setSpeaker(on: Boolean) = CallAudioRouter.setSpeaker(on)

    actual fun toggleSpeaker() = CallAudioRouter.setSpeaker(!_speakerOn.value)

    /**
     * 跳到「应用详情」页。麦克风被「不再询问」拒绝后，系统不会再把弹窗给我们，
     * 这里是用户唯一的自救入口，所以取不到 Context 时返回 false（UI 据此不渲染按钮）。
     */
    actual fun openAppSettings(): Boolean {
        val ctx = im.client.db.appContext ?: return false
        return runCatching {
            val intent = Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:" + ctx.packageName),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
            true
        }.getOrElse {
            Log.w("CallMediaExtras", "打开应用设置页失败", it)
            false
        }
    }

    /** MediaSession：重连状态（RoomEvent.Reconnecting/Reconnected） */
    internal fun setReconnecting(value: Boolean) {
        _reconnecting.value = value
    }

    /** MediaSession：麦克风被永久拒绝（UI 显示「去设置」） */
    internal fun setMicBlocked(value: Boolean) {
        if (_micPermissionBlocked.value != value) _micPermissionBlocked.value = value
    }

    /** CallAudioRouter：免提的真实硬件状态回写 */
    internal fun setSpeakerOn(value: Boolean) {
        if (_speakerOn.value != value) _speakerOn.value = value
    }
}
