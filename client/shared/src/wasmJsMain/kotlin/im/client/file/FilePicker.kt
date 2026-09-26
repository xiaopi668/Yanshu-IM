package im.client.file

import kotlinx.browser.document
import kotlinx.coroutines.suspendCancellableCoroutine
import org.w3c.dom.HTMLInputElement
import org.w3c.files.File
import org.w3c.files.FileReader
import kotlin.coroutines.resume
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@OptIn(ExperimentalEncodingApi::class)
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
                val res = reader.result
                if (res is String) {
                    val b64 = res.substringAfter("base64,", "")
                    if (b64.isNotEmpty()) {
                        cont.resume(
                            PickedFile(
                                name = (file as File).name,
                                mime = (file as File).type.ifEmpty { "application/octet-stream" },
                                bytes = Base64.decode(b64),
                            )
                        )
                    } else {
                        cont.resume(null)
                    }
                } else {
                    cont.resume(null)
                }
            }
            reader.readAsDataURL(file)
        }
        document.body?.removeChild(input)
    }
    input.click()
}
