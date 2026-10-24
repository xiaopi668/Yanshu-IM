package im.app

import android.os.Build
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

/**
 * Android 12（API 31）起支持 Material You 动态取色：整套配色跟随用户壁纸。
 * 低版本返回 null，回落到品牌色推导的方案。
 */
@Composable
actual fun dynamicColorScheme(dark: Boolean): ColorScheme? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val ctx = LocalContext.current
    return if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
}
