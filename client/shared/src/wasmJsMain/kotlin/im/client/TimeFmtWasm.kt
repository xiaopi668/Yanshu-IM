package im.client

/**
 * 浏览器时区偏移。
 * Kotlin/Wasm 里没有现成的 Date 类型，跟 Turnstile.kt 一样用 @JsFun 走单表达式。
 * Date#getTimezoneOffset 返回「UTC - 本地」（分钟，东半球为负），所以取反。
 */
@JsFun("(t) => -new Date(t).getTimezoneOffset() * 60000")
private external fun jsOffsetMs(t: Double): Double

actual fun localOffsetMs(atEpochMs: Long): Long = jsOffsetMs(atEpochMs.toDouble()).toLong()
