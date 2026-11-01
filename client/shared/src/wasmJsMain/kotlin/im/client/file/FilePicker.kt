package im.client.file

import kotlinx.browser.document
import kotlinx.coroutines.suspendCancellableCoroutine
import org.w3c.dom.HTMLInputElement
import org.w3c.files.File
import org.w3c.files.FileReader
import kotlin.coroutines.resume
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Web 端文件选择。
 *
 * 两个坑：
 * 1) input.click() 必须发生在用户手势的同一个调用栈里，否则浏览器直接忽略
 *    （调用方用 CoroutineStart.UNDISPATCHED 启动协程来保证这点）；
 * 2) 取消对话框不触发 change，协程会永远挂起 —— 额外监听 cancel 事件。
 */
@OptIn(ExperimentalEncodingApi::class)
actual suspend fun pickFile(): PickedFile? = suspendCancellableCoroutine { cont ->
    val input = document.createElement("input") as HTMLInputElement
    input.type = "file"
    input.style.display = "none"
    document.body?.appendChild(input)

    var done = false
    fun finish(v: PickedFile?) {
        if (done) return
        done = true
        runCatching { document.body?.removeChild(input) }
        cont.resume(v)
    }

    input.onchange = {
        val files = input.files
        val file = if (files != null && files.length > 0) files.item(0) else null
        if (file == null) {
            finish(null)
        } else {
            val f = file as File
            val reader = FileReader()
            reader.onload = { _ ->
                val text = reader.result?.toString() ?: ""
                val b64 = if (text.contains("base64,")) text.substringAfter("base64,") else ""
                if (b64.isEmpty()) {
                    println("[pickFile] 不是 data URL，放弃")
                    finish(null)
                } else {
                    val bytes = runCatching { Base64.decode(b64) }.getOrElse {
                        println("[pickFile] base64 解码失败: " + it)
                        ByteArray(0)
                    }
                    if (bytes.isEmpty()) {
                        finish(null)
                    } else {
                        finish(PickedFile(f.name, f.type.ifEmpty { "application/octet-stream" }, bytes))
                    }
                }
            }
            reader.onerror = { _ ->
                println("[pickFile] FileReader 出错")
                finish(null)
            }
            reader.readAsDataURL(f)
        }
    }
    input.addEventListener("cancel", { finish(null) })
    input.click()
}
