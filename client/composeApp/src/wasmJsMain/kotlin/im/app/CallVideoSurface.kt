package im.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import im.client.call.createMediaSession

/**
 * Web 端视频画面。
 *
 * Compose 画不了 DOM 视频层，所以这里只做一个占位 Box，把**它在窗口中的位置**回传给
 * 媒体层，由那层用 fixed 定位的 DOM 视频层盖上去。本地小窗由 JS 自己摆在远端画面的
 * 右下角，因此这里只处理 Remote。
 */
@Composable
actual fun CallVideoSurface(role: CallVideoRole, modifier: Modifier) {
    val density = LocalDensity.current
    val session = remember { runCatching { createMediaSession() }.getOrNull() }

    Box(
        modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .onGloballyPositioned { coords ->
                if (role != CallVideoRole.Remote) return@onGloballyPositioned
                val d = density.density
                val b = coords.boundsInWindow()
                // Compose px → CSS px：HiDPI 下不换算是错的（人机验证小组件踩过）
                session?.placeVideoSurfaces(
                    (b.left / d).toInt(), (b.top / d).toInt(),
                    (b.width / d).toInt(), (b.height / d).toInt(),
                    96,
                )
            },
    ) {
        Box(Modifier.fillMaxSize())
    }
}
