package im.app

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 色调板是 Material You 的地基：整套配色都从它推导。
 * 这些断言检查「推导规则本身对不对」，而不是某个具体色值 —— 换种子色时不需要改测试。
 */
class TonalPaletteTest {

    private val seed = Color(0xFF2563EB) // 品牌蓝
    private val primary = TonalPalette.primary(seed)
    private val neutral = TonalPalette.neutral(seed)

    /** tone 数字越大越亮（这是 M3 全部角色表的前提） */
    @Test
    fun toneIsMonotonicInLuminance() {
        val tones = listOf(0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 95, 100)
        val luminances = tones.map { primary.tone(it).luminance() }
        for (i in 1 until luminances.size) {
            assertTrue(
                luminances[i] > luminances[i - 1],
                "tone 应从暗到亮递增，在 tone=" + tones[i] + " 处断裂",
            )
        }
    }

    /** 两端点：tone(100) 接近纯白、tone(0) 接近纯黑 */
    @Test
    fun toneExtremesAreWhiteAndBlack() {
        assertTrue(primary.tone(100).luminance() > 0.95, "tone(100) 应接近白")
        assertTrue(primary.tone(0).luminance() < 0.02, "tone(0) 应接近黑")
    }

    /** 种子是蓝色时，主色板在各 tone 上都应偏蓝（色相不漂） */
    @Test
    fun hueStaysBlueAcrossTones() {
        for (tone in listOf(20, 30, 40, 60, 80)) {
            val c = primary.tone(tone)
            assertTrue(
                c.blue > c.red && c.blue > c.green,
                "tone=" + tone + " 应偏蓝，实际 r=" + c.red + " g=" + c.green + " b=" + c.blue,
            )
        }
    }

    /** 中性板必须足够「灰」：大面积底色带色相会显得脏 */
    @Test
    fun neutralPaletteIsNearlyGray() {
        for (tone in listOf(12, 30, 90, 94, 96, 98)) {
            val c = neutral.tone(tone)
            val spread = maxOf(c.red, c.green, c.blue) - minOf(c.red, c.green, c.blue)
            assertTrue(spread < 0.06f, "tone=" + tone + " 中性色饱和度太高（通道差 " + spread + "）")
        }
    }

    /** M3 要求可用性：onPrimary(100) 压在 primary(40) 上够对比度（WCAG AA 4.5:1） */
    @Test
    fun onPrimaryHasAccessibleContrast() {
        val ratio = contrastRatio(primary.tone(100), primary.tone(40))
        assertTrue(ratio >= 4.5, "onPrimary 对 primary 的对比度只有 " + ratio)
    }

    /** 深色模式下 onPrimary(20) 压在 primary(80) 上同样要够 */
    @Test
    fun darkOnPrimaryHasAccessibleContrast() {
        val ratio = contrastRatio(primary.tone(20), primary.tone(80))
        assertTrue(ratio >= 4.5, "深色模式对比度只有 " + ratio)
    }

    /** tone 超出 0..100 时应被夹紧，而不是产生非法颜色 */
    @Test
    fun toneOutOfRangeIsClamped() {
        assertTrue(primary.tone(-20).luminance() < 0.02, "负数 tone 应夹到黑")
        assertTrue(primary.tone(200).luminance() > 0.95, "超过 100 的 tone 应夹到白")
    }

    private fun contrastRatio(a: Color, b: Color): Double {
        val la = a.luminance().toDouble()
        val lb = b.luminance().toDouble()
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }
}
