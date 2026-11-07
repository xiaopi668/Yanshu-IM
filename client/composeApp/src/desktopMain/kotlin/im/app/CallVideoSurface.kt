package im.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/** 桌面端目前没有视频媒体面（Rust 侧车只做音频），如实显示占位 */
@Composable
actual fun CallVideoSurface(role: CallVideoRole, modifier: Modifier) {
    Box(
        modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.fillMaxSize())
        if (role == CallVideoRole.Remote) {
            Text(
                "桌面端暂不支持视频",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
