package im.app

import android.content.Context
import android.content.SharedPreferences
import im.client.db.appContext

private const val FILE = "yanshu_session"

private fun prefs(): SharedPreferences? = appContext?.getSharedPreferences(FILE, Context.MODE_PRIVATE)

actual fun loadAccentSeed(): String? {
    return try {
        prefs()?.getString("accent", "")?.takeIf { it.isNotBlank() }
    } catch (_: Throwable) {
        null
    }
}

actual fun saveAccentSeed(hex: String?) {
    try {
        if (hex == null) prefs()?.edit()?.remove("accent")?.apply()
        else prefs()?.edit()?.putString("accent", hex)?.apply()
    } catch (_: Throwable) {
    }
}
