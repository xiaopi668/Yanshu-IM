package im.app

import androidx.compose.runtime.Composable

/** 桌面 / Android 有系统字体（含中文），不需要加载 */
@Composable
actual fun appFontState(): AppFontState = AppFontState.Ready(null)
