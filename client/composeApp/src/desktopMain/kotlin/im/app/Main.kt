package im.app

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.Toolkit

fun main() {
    application {
        // niri 的 XWayland 集成会放大 X11 初始窗口且 resize 事件与应用失联，
        // surface 停留在初始尺寸 → 内容缩在左上角。
        // 缓解：初始就把窗口开到接近屏幕可用区的规格，surface 与真实窗口基本重合。
        val screen = java.awt.Toolkit.getDefaultToolkit().screenSize
        val width = (screen.width * 0.55).toInt().dp
        val height = (screen.height * 0.98).toInt().dp
        Window(
            onCloseRequest = ::exitApplication,
            title = "IM",
            state = rememberWindowState(width = width, height = height),
        ) {
            AppRoot()
        }
    }
}
