package im.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import im.client.call.AndroidCallVideo

/**
 * Android 视频画面。
 *
 * 注意：这里**只**通过 AndroidCallVideo 拿/放 View，不直接引用 livekit 的渲染器类型 ——
 * composeApp 并不依赖 livekit-android（它在 shared 里是 implementation），
 * 而且 UI 层本来也不该认识媒体 SDK。
 *
 * SurfaceView 是独立图层：**外层不要再套 clip**，否则黑屏。圆角由调用方的背景层负责。
 */
@Composable
actual fun CallVideoSurface(role: CallVideoRole, modifier: Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            if (role == CallVideoRole.Remote) AndroidCallVideo.createRemote(ctx)
            else AndroidCallVideo.createLocal(ctx)
        },
        // 视图离开组合必须解绑 + release，否则轨道会一直持有 renderer（泄漏 EGL 上下文）
        onRelease = { view ->
            if (role == CallVideoRole.Remote) AndroidCallVideo.destroyRemote(view)
            else AndroidCallVideo.destroyLocal(view)
        },
    )
}
