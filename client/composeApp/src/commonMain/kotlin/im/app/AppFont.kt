package im.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily

/**
 * 应用字体状态。
 *
 * 为什么需要「加载中」这个状态：Web 端（Compose/Wasm）没有系统字体，中文字形要么来自
 * 随包字体、要么来自运行时下载的回退字体。如果在字体就绪前就绘制文字，Compose 会按
 * 「缺字形」渲染成方框（口口口）**并缓存排版结果** —— 之后即使字体到了也不会自动重排，
 * 用户看到的就是「必须把鼠标移上去（触发重绘）才变正常」。
 *
 * 所以 Web 端在 Loading 期间不绘制界面；桌面 / Android 用系统字体，直接 Ready(null)。
 */
sealed interface AppFontState {
    /** 字体仍在加载（仅 Web 端会出现） */
    data object Loading : AppFontState

    /** 就绪。family 为 null 表示「继续用系统默认字体」 */
    data class Ready(val family: FontFamily?) : AppFontState
}

@Composable
expect fun appFontState(): AppFontState
