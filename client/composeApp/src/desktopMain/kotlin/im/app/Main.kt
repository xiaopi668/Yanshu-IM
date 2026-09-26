package im.app

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

fun main() {
    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "IM",
            state = rememberWindowState(width = 960.dp, height = 680.dp),
        ) {
            AppRoot()
            // KDE Wayland 会把 X11 初始窗口放大且不通知 surface（内容缩在左上角，
            // 其余黑屏）。启动后强制重发一次尺寸请求，让合成器与内容重新对齐。
            LaunchedEffect(Unit) {
                withContext(Dispatchers.Default) {
                    delay(2000)
                    javax.swing.SwingUtilities.invokeLater {
                        for (w in java.awt.Window.getWindows()) {
                            val f = w as? java.awt.Frame ?: continue
                            if (!f.isShowing) continue
                            val ww = f.width
                            val hh = f.height
                            f.setSize(ww + 1, hh)   // 触发真实的 X resize
                            javax.swing.SwingUtilities.invokeLater {
                                f.setSize(ww, hh)  // 回到预期尺寸
                            }
                        }
                    }
                }
            }
        }
    }
}
