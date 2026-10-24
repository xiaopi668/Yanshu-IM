package im.app

import androidx.compose.ui.graphics.Color
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Material You 的色调板（tonal palette）。
 *
 * M3 的整套配色不是手挑的，而是「从一个种子色推导出一族同色相、不同明度的颜色」：
 * 每个角色取固定的 tone（CIE L*，0=黑 100=白），色相与彩度跟随种子色。
 *
 * 关键点：**必须在线性空间/Lab 里调明度**。直接在 sRGB 上乘系数会同时改变色相与饱和度，
 * 深色模式下就会发灰发脏 —— 这也是手写配色最常见的翻车点。
 */
class TonalPalette private constructor(
    private val hue: Double,      // CIE Lab 色相角（度）
    private val chroma: Double,   // 目标彩度，实际取值时会按色域裁剪
) {
    /** 取某个 tone（CIE L*，0=黑 100=白）的颜色；超出 sRGB 色域时二分降低彩度 */
    fun tone(tone: Int): Color {
        val l = tone.coerceIn(0, 100).toDouble()
        var c = chroma
        var rgb = labToRgb(l, c, hue)
        if (!inGamut(rgb)) {
            var lo = 0.0
            var hi = c
            repeat(14) {
                val mid = (lo + hi) / 2
                val candidate = labToRgb(l, mid, hue)
                if (inGamut(candidate)) { lo = mid; rgb = candidate } else hi = mid
            }
            c = lo
        }
        return Color(
            red = rgb[0].coerceIn(0.0, 1.0).toFloat(),
            green = rgb[1].coerceIn(0.0, 1.0).toFloat(),
            blue = rgb[2].coerceIn(0.0, 1.0).toFloat(),
            alpha = 1f,
        )
    }

    companion object {
        /** 主色板：彩度跟随种子 */
        fun primary(seed: Color): TonalPalette {
            val lab = rgbToLab(seed.red.toDouble(), seed.green.toDouble(), seed.blue.toDouble())
            val c = sqrt(lab[1] * lab[1] + lab[2] * lab[2])
            val h = ((atan2(lab[2], lab[1]) * 180.0 / PI) + 360.0) % 360.0
            return TonalPalette(h, c.coerceIn(36.0, 70.0))
        }

        /** 中性色板：用于 surface / onSurface 等大面积底色，彩度极低 */
        fun neutral(seed: Color, chroma: Double = 4.0): TonalPalette =
            TonalPalette(hueOf(seed), chroma)

        /** 中性变体：surfaceVariant / outline 等，比中性略有色相但不喧宾夺主 */
        fun neutralVariant(seed: Color): TonalPalette = TonalPalette(hueOf(seed), 8.0)

        private fun hueOf(seed: Color): Double {
            val lab = rgbToLab(seed.red.toDouble(), seed.green.toDouble(), seed.blue.toDouble())
            return ((atan2(lab[2], lab[1]) * 180.0 / PI) + 360.0) % 360.0
        }
    }
}

// ---------- sRGB ↔ 线性 ↔ XYZ ↔ Lab ----------

private fun srgbToLinear(c: Double): Double =
    if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

private fun linearToSrgb(c: Double): Double =
    if (c <= 0.0031308) c * 12.92 else 1.055 * c.coerceAtLeast(0.0).pow(1.0 / 2.4) - 0.055

private fun inGamut(rgb: DoubleArray): Boolean = rgb.all { it >= -0.001 && it <= 1.001 }

/** sRGB(0..1) → CIE Lab（D65） */
private fun rgbToLab(r: Double, g: Double, b: Double): DoubleArray {
    val rl = srgbToLinear(r); val gl = srgbToLinear(g); val bl = srgbToLinear(b)
    val x = (rl * 0.4124564 + gl * 0.3575761 + bl * 0.1804375) / 0.95047
    val y = (rl * 0.2126729 + gl * 0.7151522 + bl * 0.0721750) / 1.00000
    val z = (rl * 0.0193339 + gl * 0.1191920 + bl * 0.9503041) / 1.08883
    fun f(t: Double) = if (t > 0.008856) t.pow(1.0 / 3.0) else (903.3 * t + 16.0) / 116.0
    val fx = f(x); val fy = f(y); val fz = f(z)
    return doubleArrayOf(116.0 * fy - 16.0, 500.0 * (fx - fy), 200.0 * (fy - fz))
}

/** CIE Lab → sRGB(0..1)，可能超出色域（由调用方裁剪） */
private fun labToRgb(l: Double, chroma: Double, hueDeg: Double): DoubleArray {
    val rad = hueDeg * PI / 180.0
    val a = chroma * cos(rad)
    val bb = chroma * sin(rad)
    val fy = (l + 16.0) / 116.0
    val fx = fy + a / 500.0
    val fz = fy - bb / 200.0
    fun finv(t: Double): Double {
        val t3 = t * t * t
        return if (t3 > 0.008856) t3 else (116.0 * t - 16.0) / 903.3
    }
    val x = finv(fx) * 0.95047
    val y = finv(fy) * 1.00000
    val z = finv(fz) * 1.08883
    val rl = x * 3.2404542 + y * -1.5371385 + z * -0.4985314
    val gl = x * -0.9692660 + y * 1.8760108 + z * 0.0415560
    val bl = x * 0.0556434 + y * -0.2040259 + z * 1.0572252
    return doubleArrayOf(linearToSrgb(rl), linearToSrgb(gl), linearToSrgb(bl))
}
