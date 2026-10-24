package im.app

import androidx.compose.ui.unit.dp
import im.app.resources.Res
import im.app.resources.logo
import org.jetbrains.compose.resources.painterResource
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.Toolkit

fun main() {
    application {
        // 说明：桌面端跑在 Wayland 会话的 XWayland（niri 为 xwayland-satellite）路径上。
        // 初始尺寸必须按「屏幕逻辑尺寸」算（见下方 scale 换算），否则 HiDPI 下窗口会比屏幕大，
        // 右侧/底部的界面会被裁掉。
        // OpenJDK 的 Wayland 原生 toolkit（-Dawt.toolkit.name=WLToolkit）在 JDK 21/25 均会
        // 静默回退 X11，且在内置 runtime 下会导致崩溃，勿开启；待 JDK/Compose 官方适配后再启用。
        // AWT 的 screenSize 返回的是**物理像素**，而 Compose 的 dp 受系统缩放影响。
        // 直接把物理像素当 dp 用，在 HiDPI（缩放 2x）下会申请 2 倍大的窗口：
        // 窗口管理器把它裁到屏幕大小，于是内容只有左半边可见 ——
        // 表现就是「自己发的消息被右边缘切掉」「输入栏被底部切掉」。
        val screen = Toolkit.getDefaultToolkit().screenSize
        val scale = runCatching {
            java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                .defaultScreenDevice.defaultConfiguration.defaultTransform.scaleX
        }.getOrDefault(1.0)
        val width = (screen.width / scale * 0.55).toInt().dp
        val height = (screen.height / scale * 0.98).toInt().dp
        Window(
            onCloseRequest = ::exitApplication,
            title = "雁书",
            // 窗口/任务栏图标与品牌 logo 保持一致
            icon = painterResource(Res.drawable.logo),
            state = rememberWindowState(width = width, height = height),
        ) {
            AppRoot()
        }
    }
}
