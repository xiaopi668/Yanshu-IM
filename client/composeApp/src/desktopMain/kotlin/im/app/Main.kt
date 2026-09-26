package im.app

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.Toolkit

fun main() {
    application {
        // 说明：桌面端目前跑在 Wayland 会话的 XWayland（niri 为 xwayland-satellite）路径上。
        // niri 会放大 X11 初始窗口且 resize 事件与应用失联，故初始规格按屏幕可用区取值，
        // 让渲染 surface 与合成器生成的实际窗口重合（详见 README「已知边界」）。
        // OpenJDK 的 Wayland 原生 toolkit（-Dawt.toolkit.name=WLToolkit）在 JDK 21/25 均会
        // 静默回退 X11，且在内置 runtime 下会导致崩溃，勿开启；待 JDK/Compose 官方适配后再启用。
        val screen = Toolkit.getDefaultToolkit().screenSize
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
