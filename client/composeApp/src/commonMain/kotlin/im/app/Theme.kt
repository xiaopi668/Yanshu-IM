package im.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// ---------- Material You（Material Design 3） ----------
//
// M3 的配色不是手挑的，而是「一个种子色 → 一族同色相不同明度的颜色 → 按角色取 tone」。
// 因此整套方案只需要一个输入：种子色。换种子色，全站自动重新协调。
//
// 种子从哪来：
//   - Android 12+：默认取系统壁纸（动态取色）；
//   - 其它平台（Web/桌面）：没有壁纸这个概念，用户可以自己选一个 —— 这就是 M3 在 Web 上的做法；
//   - 用户显式选过颜色时，优先用他选的那个（动态取色可以被用户覆盖）。

/** 默认种子：品牌蓝（新 logo 主色） */
const val DEFAULT_SEED_HEX = "2563EB"

/** 预设主题色（用户可选） */
val ACCENT_PRESETS = listOf(
    "2563EB" to "品牌蓝",
    "00897B" to "青",
    "6D4C9F" to "紫",
    "C2185B" to "玫红",
    "E65100" to "橙",
    "2E7D32" to "绿",
)

private const val ERROR_SEED_HEX = "B3261E"

/** 解析 "RRGGBB" / "#RRGGBB"；非法值回落到默认种子 */
fun parseSeedHex(hex: String): Color {
    val h = hex.trim().removePrefix("#")
    if (h.length != 6) return Color(0xFF2563EB)
    val v = h.toLongOrNull(16) ?: return Color(0xFF2563EB)
    return Color(0xFF000000 or v)
}

/**
 * 由种子色推导完整方案。结果按 (种子, 明暗) 缓存 ——
 * 色调板生成含色域裁剪，不适合每次重组都算一遍。
 */
private val schemeCache = mutableMapOf<String, ColorScheme>()

fun materialYouScheme(seedHex: String, dark: Boolean): ColorScheme {
    val key = seedHex.trim().removePrefix("#").lowercase() + if (dark) ":dark" else ":light"
    return schemeCache.getOrPut(key) { buildScheme(parseSeedHex(seedHex), dark) }
}

private fun buildScheme(seed: Color, dark: Boolean): ColorScheme {
    val primary = TonalPalette.primary(seed)
    val secondary = TonalPalette.secondary(seed)
    val tertiary = TonalPalette.tertiary(seed)
    val neutral = TonalPalette.neutral(seed)
    val neutralVariant = TonalPalette.neutralVariant(seed)
    val error = TonalPalette.primary(parseSeedHex(ERROR_SEED_HEX))

    return if (dark) darkColorScheme(
        primary = primary.tone(80),
        onPrimary = primary.tone(20),
        primaryContainer = primary.tone(30),
        onPrimaryContainer = primary.tone(90),
        inversePrimary = primary.tone(40),
        secondary = secondary.tone(80),
        onSecondary = secondary.tone(20),
        secondaryContainer = secondary.tone(30),
        onSecondaryContainer = secondary.tone(90),
        tertiary = tertiary.tone(80),
        onTertiary = tertiary.tone(20),
        tertiaryContainer = tertiary.tone(30),
        onTertiaryContainer = tertiary.tone(90),
        error = error.tone(80),
        onError = error.tone(20),
        errorContainer = error.tone(30),
        onErrorContainer = error.tone(90),
        background = neutral.tone(6),
        onBackground = neutral.tone(90),
        surface = neutral.tone(6),
        onSurface = neutral.tone(90),
        surfaceVariant = neutralVariant.tone(30),
        onSurfaceVariant = neutralVariant.tone(80),
        surfaceTint = primary.tone(80),
        inverseSurface = neutral.tone(90),
        inverseOnSurface = neutral.tone(20),
        outline = neutralVariant.tone(60),
        outlineVariant = neutralVariant.tone(30),
        scrim = neutral.tone(0),
        surfaceBright = neutral.tone(24),
        surfaceDim = neutral.tone(6),
        surfaceContainerLowest = neutral.tone(4),
        surfaceContainerLow = neutral.tone(10),
        surfaceContainer = neutral.tone(12),
        surfaceContainerHigh = neutral.tone(17),
        surfaceContainerHighest = neutral.tone(22),
    ) else lightColorScheme(
        primary = primary.tone(40),
        onPrimary = primary.tone(100),
        primaryContainer = primary.tone(90),
        onPrimaryContainer = primary.tone(10),
        inversePrimary = primary.tone(80),
        secondary = secondary.tone(40),
        onSecondary = secondary.tone(100),
        secondaryContainer = secondary.tone(90),
        onSecondaryContainer = secondary.tone(10),
        tertiary = tertiary.tone(40),
        onTertiary = tertiary.tone(100),
        tertiaryContainer = tertiary.tone(90),
        onTertiaryContainer = tertiary.tone(10),
        error = error.tone(40),
        onError = error.tone(100),
        errorContainer = error.tone(90),
        onErrorContainer = error.tone(10),
        background = neutral.tone(98),
        onBackground = neutral.tone(10),
        surface = neutral.tone(98),
        onSurface = neutral.tone(10),
        surfaceVariant = neutralVariant.tone(90),
        onSurfaceVariant = neutralVariant.tone(30),
        surfaceTint = primary.tone(40),
        inverseSurface = neutral.tone(20),
        inverseOnSurface = neutral.tone(95),
        outline = neutralVariant.tone(50),
        outlineVariant = neutralVariant.tone(80),
        scrim = neutral.tone(0),
        surfaceBright = neutral.tone(98),
        surfaceDim = neutral.tone(87),
        surfaceContainerLowest = neutral.tone(100),
        surfaceContainerLow = neutral.tone(96),
        surfaceContainer = neutral.tone(94),
        surfaceContainerHigh = neutral.tone(92),
        surfaceContainerHighest = neutral.tone(90),
    )
}

/** M3 标准圆角档位 */
private val YanshuShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * 全局主题。
 *
 * @param seedHex 用户显式选的主题色；传 null 表示「跟随平台默认」——
 *                Android 12+ 用壁纸动态取色，其它平台用品牌色。
 */
@Composable
fun YanshuTheme(
    dark: Boolean = isSystemInDarkTheme(),
    seedHex: String? = null,
    content: @Composable () -> Unit,
) {
    val scheme: ColorScheme = if (seedHex != null) {
        // 用户选过的颜色优先：动态取色也要能被覆盖，否则「我选了红色却不生效」
        remember(seedHex, dark) { materialYouScheme(seedHex, dark) }
    } else {
        dynamicColorScheme(dark) ?: remember(dark) { materialYouScheme(DEFAULT_SEED_HEX, dark) }
    }
    MaterialTheme(colorScheme = scheme, shapes = YanshuShapes, content = content)
}

/**
 * 平台动态取色：Android 12+（API 31）返回跟随壁纸的配色，其余平台返回 null。
 */
@Composable
expect fun dynamicColorScheme(dark: Boolean): ColorScheme?
