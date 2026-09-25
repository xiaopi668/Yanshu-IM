package im.client

import android.content.Intent
import android.net.Uri

actual fun openUrl(url: String) {
    try {
        Intent(Intent.ACTION_VIEW, Uri.parse(url)).also { }
        // 实际打开需要 Activity context，M4 接入
    } catch (_: Throwable) {
    }
}
