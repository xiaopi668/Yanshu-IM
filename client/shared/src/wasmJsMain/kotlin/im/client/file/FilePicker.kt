package im.client.file

import kotlinx.browser.document
import kotlinx.coroutines.suspendCancellableCoroutine
import org.khronos.webgl.Int8Array
import org.w3c.dom.HTMLInputElement
import org.w3c.files.File
import org.w3c.files.FileReader
import kotlin.coroutines.resume

actual suspend fun pickFile(): PickedFile? = suspendCancellableCoroutine { cont ->
    val input = document.createElement("input") as HTMLInputElement
    input.type = "file"
    input.style.display = "none"
    document.body?.appendChild(input)
    input.onchange = {
        val files = input.files
        val file = if (files != null && files.length > 0) files.item(0) else null
        if (file == null) {
            cont.resume(null)
        } else {
            val reader = FileReader()
            reader.onload = { _ ->
                val buf = reader.result
                if (buf is org.w3c.dom.ArrayBuffer) {
                    val arr = Int8Array(buf)
                    val bytes = ByteArray(arr.length) { arr[it] }
                    cont.resume(
                        PickedFile(
                            name = (file as File).name,
                            mime = (file as File).type.ifEmpty { "application/octet-stream" },
                            bytes = bytes,
                        )
                    )
                } else {
                    cont.resume(null)
                }
            }
            reader.readAsArrayBuffer(file)
        }
        document.body?.removeChild(input)
    }
    input.click()
}
