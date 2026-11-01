package im.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily

/** 桌面 / Android 有系统字体（含中文），继续用默认字体 */
@Composable
actual fun appFontFamily(): FontFamily? = null
