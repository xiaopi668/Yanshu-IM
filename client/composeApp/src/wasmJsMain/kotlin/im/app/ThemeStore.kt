package im.app

import kotlinx.browser.window

private const val K_ACCENT = "yanshu.accent"

actual fun loadAccentSeed(): String? = try {
    window.localStorage.getItem(K_ACCENT)?.takeIf { it.isNotBlank() }
} catch (_: Throwable) {
    null // 隐身模式 / 被策略禁用
}

actual fun saveAccentSeed(hex: String?) {
    try {
        if (hex == null) window.localStorage.removeItem(K_ACCENT)
        else window.localStorage.setItem(K_ACCENT, hex)
    } catch (_: Throwable) {
    }
}
