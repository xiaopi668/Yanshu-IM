package im.client.file

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import java.io.File
import kotlin.coroutines.resume

actual suspend fun pickFile(): PickedFile? = withContext(Dispatchers.Default) {
    suspendCancellableCoroutine { cont ->
        SwingUtilities.invokeLater {
            try {
                val chooser = JFileChooser()
                val result = chooser.showOpenDialog(null)
                if (result == JFileChooser.APPROVE_OPTION) {
                    val f: File = chooser.selectedFile
                    cont.resumeWith(
                        Result.success(
                            PickedFile(
                                name = f.name,
                                mime = guessMime(f.name),
                                bytes = f.readBytes(),
                            )
                        )
                    )
                } else {
                    cont.resumeWith(Result.success(null))
                }
            } catch (e: Throwable) {
                cont.resumeWith(Result.success(null))
            }
        }
    }
}

private fun guessMime(name: String): String = when {
    name.endsWith(".png", true) -> "image/png"
    name.endsWith(".jpg", true) || name.endsWith(".jpeg", true) -> "image/jpeg"
    name.endsWith(".gif", true) -> "image/gif"
    name.endsWith(".webp", true) -> "image/webp"
    name.endsWith(".pdf", true) -> "application/pdf"
    name.endsWith(".txt", true) -> "text/plain"
    else -> "application/octet-stream"
}
