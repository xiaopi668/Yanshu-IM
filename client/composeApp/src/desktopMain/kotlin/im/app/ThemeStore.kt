package im.app

import java.util.prefs.Preferences

private const val NODE = "im/yanshu"

actual fun loadAccentSeed(): String? = try {
    Preferences.userRoot().node(NODE).get("accent", "")?.takeIf { it.isNotBlank() }
} catch (_: Throwable) {
    null
}

actual fun saveAccentSeed(hex: String?) {
    try {
        val p = Preferences.userRoot().node(NODE)
        if (hex == null) p.remove("accent") else p.put("accent", hex)
        p.flush()
    } catch (_: Throwable) {
    }
}
