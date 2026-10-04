package im.client

import android.content.Intent
import android.net.Uri
import im.client.db.appContext

actual fun openUrl(url: String) {
    // appContext 是 application context（MainActivity.onCreate 里赋值），必须加 NEW_TASK
    val ctx = appContext ?: return
    try {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (_: Throwable) {
    }
}
