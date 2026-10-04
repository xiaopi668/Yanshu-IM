package im.client.file

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Android 选文件的桥。
 *
 * Activity Result API 的 launcher 必须由 Activity 注册，而 shared 层拿不到 Activity，
 * 所以由 MainActivity 在创建时把两样东西装进来：
 *   - launch：拉起系统文件选择器（SAF，返回 content:// URI）
 *   - resolver：读内容（应用级 ContentResolver，不持有 Activity，不会泄漏）
 *
 * 之前这里是 `actual fun pickFile(): PickedFile? = null` —— Android 端**根本选不了文件**：
 * 发图片、发文件、换头像、发带图动态全部不可用。
 */
object AndroidFilePicker {
    /** 由 MainActivity 注入：拉起选择器，参数是允许的 MIME 类型 */
    var launch: ((Array<String>) -> Unit)? = null

    /** 由 MainActivity 注入：应用级 ContentResolver */
    var resolver: ContentResolver? = null

    /** 当前等待中的请求（同一时刻只允许一个） */
    private var pending: ((Uri?) -> Unit)? = null

    /** MainActivity 拿到结果后回调；传 null 表示用户取消 */
    fun onResult(uri: Uri?) {
        val cb = pending ?: return
        pending = null
        cb(uri)
    }

    /** 挂起直到拿到用户选择的 URI（取消、或未接入时返回 null） */
    internal suspend fun awaitUri(): Uri? = suspendCancellableCoroutine { cont ->
        val launcher = launch
        if (launcher == null || pending != null) {
            // 未接入（如后台环境），或上一次选择还没结束
            cont.resume(null)
            return@suspendCancellableCoroutine
        }
        pending = { uri -> if (cont.isActive) cont.resume(uri) }
        cont.invokeOnCancellation { pending = null }
        launcher(arrayOf("*/*"))
    }
}

actual suspend fun pickFile(): PickedFile? {
    val uri = AndroidFilePicker.awaitUri() ?: return null
    val resolver = AndroidFilePicker.resolver ?: return null
    // 读文件必须离开主线程：大图/大文件在主线程读会直接 ANR
    return withContext(Dispatchers.IO) {
        runCatching {
            val name = queryDisplayName(resolver, uri)
            val mime = resolver.getType(uri)?.takeIf { it.isNotBlank() } ?: guessMime(name)
            val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return@runCatching null
            PickedFile(name = name, mime = mime, bytes = bytes)
        }.getOrNull()
    }
}

/** SAF 的显示名要从 OpenableColumns 查，URI 末段通常只是一串数字 */
private fun queryDisplayName(resolver: ContentResolver, uri: Uri): String {
    runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) {
                    c.getString(idx)?.takeIf { it.isNotBlank() }?.let { return it }
                }
            }
        }
    }
    return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "file"
}

private fun guessMime(name: String): String = when {
    name.endsWith(".png", true) -> "image/png"
    name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) -> "image/jpeg"
    name.endsWith(".gif", true) -> "image/gif"
    name.endsWith(".webp", true) -> "image/webp"
    name.endsWith(".mp4", true) -> "video/mp4"
    name.endsWith(".m4a", true) || name.endsWith(".mp3", true) -> "audio/mpeg"
    name.endsWith(".pdf", true) -> "application/pdf"
    name.endsWith(".txt", true) -> "text/plain"
    else -> "application/octet-stream"
}
