package im.app

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable

/** Web 端没有系统级动态取色，回落到品牌色推导的方案 */
@Composable
actual fun dynamicColorScheme(dark: Boolean): ColorScheme? = null
