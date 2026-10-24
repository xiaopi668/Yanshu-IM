package im.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// ---------- Material You（Material Design 3） ----------
//
// 三点让它「像 Material You」：
//  1. 完整的一套 M3 色调角色（含 surfaceContainer* 五档、inverse*、scrim）——
//     M3 的层级感靠底色分档表达，而不是靠投影与描边；
//  2. 配色不是手挑的，而是从品牌色推导出色调板（见 TonalPalette）；
//  3. Android 12+ 直接跟随系统壁纸取色（动态取色），其它平台用品牌色生成的静态方案。
//
// 圆角用 M3 标准的 4/8/12/16/28，按钮一律 pills（见 UI 层）。

/** 品牌种子色：新 logo 的主蓝 */
private val Seed = Color(0xFF2563EB)
/** 错误色单独一个色板，避免跟着品牌色相漂移 */
private val ErrorSeed = Color(0xFFB3261E)

private val primaryPalette = TonalPalette.primary(Seed)
private val secondaryPalette = TonalPalette.primary(Color(0xFF3D6B8C))
private val tertiaryPalette = TonalPalette.primary(Color(0xFF7C5DBF))
private val neutralPalette = TonalPalette.neutral(Seed)
private val neutralVariantPalette = TonalPalette.neutralVariant(Seed)
private val errorPalette = TonalPalette.primary(ErrorSeed)

private fun materialYouLight(): ColorScheme = lightColorScheme(
    primary = primaryPalette.tone(40),
    onPrimary = primaryPalette.tone(100),
    primaryContainer = primaryPalette.tone(90),
    onPrimaryContainer = primaryPalette.tone(10),
    inversePrimary = primaryPalette.tone(80),

    secondary = secondaryPalette.tone(40),
    onSecondary = secondaryPalette.tone(100),
    secondaryContainer = secondaryPalette.tone(90),
    onSecondaryContainer = secondaryPalette.tone(10),

    tertiary = tertiaryPalette.tone(40),
    onTertiary = tertiaryPalette.tone(100),
    tertiaryContainer = tertiaryPalette.tone(90),
    onTertiaryContainer = tertiaryPalette.tone(10),

    error = errorPalette.tone(40),
    onError = errorPalette.tone(100),
    errorContainer = errorPalette.tone(90),
    onErrorContainer = errorPalette.tone(10),

    background = neutralPalette.tone(98),
    onBackground = neutralPalette.tone(10),
    surface = neutralPalette.tone(98),
    onSurface = neutralPalette.tone(10),
    surfaceVariant = neutralVariantPalette.tone(90),
    onSurfaceVariant = neutralVariantPalette.tone(30),
    surfaceTint = primaryPalette.tone(40),

    inverseSurface = neutralPalette.tone(20),
    inverseOnSurface = neutralPalette.tone(95),

    outline = neutralVariantPalette.tone(50),
    outlineVariant = neutralVariantPalette.tone(80),
    scrim = neutralPalette.tone(0),

    surfaceBright = neutralPalette.tone(98),
    surfaceDim = neutralPalette.tone(87),
    surfaceContainerLowest = neutralPalette.tone(100),
    surfaceContainerLow = neutralPalette.tone(96),
    surfaceContainer = neutralPalette.tone(94),
    surfaceContainerHigh = neutralPalette.tone(92),
    surfaceContainerHighest = neutralPalette.tone(90),
)

private fun materialYouDark(): ColorScheme = darkColorScheme(
    primary = primaryPalette.tone(80),
    onPrimary = primaryPalette.tone(20),
    primaryContainer = primaryPalette.tone(30),
    onPrimaryContainer = primaryPalette.tone(90),
    inversePrimary = primaryPalette.tone(40),

    secondary = secondaryPalette.tone(80),
    onSecondary = secondaryPalette.tone(20),
    secondaryContainer = secondaryPalette.tone(30),
    onSecondaryContainer = secondaryPalette.tone(90),

    tertiary = tertiaryPalette.tone(80),
    onTertiary = tertiaryPalette.tone(20),
    tertiaryContainer = tertiaryPalette.tone(30),
    onTertiaryContainer = tertiaryPalette.tone(90),

    error = errorPalette.tone(80),
    onError = errorPalette.tone(20),
    errorContainer = errorPalette.tone(30),
    onErrorContainer = errorPalette.tone(90),

    background = neutralPalette.tone(6),
    onBackground = neutralPalette.tone(90),
    surface = neutralPalette.tone(6),
    onSurface = neutralPalette.tone(90),
    surfaceVariant = neutralVariantPalette.tone(30),
    onSurfaceVariant = neutralVariantPalette.tone(80),
    surfaceTint = primaryPalette.tone(80),

    inverseSurface = neutralPalette.tone(90),
    inverseOnSurface = neutralPalette.tone(20),

    outline = neutralVariantPalette.tone(60),
    outlineVariant = neutralVariantPalette.tone(30),
    scrim = neutralPalette.tone(0),

    surfaceBright = neutralPalette.tone(24),
    surfaceDim = neutralPalette.tone(6),
    surfaceContainerLowest = neutralPalette.tone(4),
    surfaceContainerLow = neutralPalette.tone(10),
    surfaceContainer = neutralPalette.tone(12),
    surfaceContainerHigh = neutralPalette.tone(17),
    surfaceContainerHighest = neutralPalette.tone(22),
)

/** 静态方案只算一次（色调板生成涉及色域裁剪，不必每帧重算） */
private val LightScheme: ColorScheme by lazy { materialYouLight() }
private val DarkScheme: ColorScheme by lazy { materialYouDark() }

/** M3 标准圆角档位 */
private val YanshuShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * 全局主题：全端共用。
 * Android 12+ 跟随壁纸动态取色；其它平台用品牌色推导的方案。
 */
@Composable
fun YanshuTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val scheme = dynamicColorScheme(dark) ?: if (dark) DarkScheme else LightScheme
    MaterialTheme(colorScheme = scheme, shapes = YanshuShapes, content = content)
}

/**
 * 平台动态取色：Android 12+（API 31）返回跟随壁纸的配色，其余平台返回 null 以回落到品牌色方案。
 */
@Composable
expect fun dynamicColorScheme(dark: Boolean): ColorScheme?
