package im.client.totp

import android.content.Context
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.FileOutputStream
import kotlin.coroutines.resume

/**
 * Android 端导出的宿主注入点。
 *
 * shared 层拿不到 Activity，所以和「选文件」「权限」一样：由 MainActivity 注册
 * SAF（ACTION_CREATE_DOCUMENT）的 launcher 再注入进来。
 */
object AndroidRecoveryExport {
    const val TAG: String = "RecoveryExport"

    /** 由 MainActivity 注入：拉起「保存到…」，用户选定后回调 Uri（取消为 null） */
    var launcher: ((suggestedName: String, onPicked: (android.net.Uri?) -> Unit) -> Unit)? = null

    /** 由 MainActivity 注入：应用级 Context（打印需要） */
    var appContext: Context? = null
}

private object AndroidExport : RecoveryExport {
    override val canSave: Boolean
        get() = AndroidRecoveryExport.launcher != null

    override val canPrint: Boolean
        get() = AndroidRecoveryExport.appContext != null

    override suspend fun save(fileName: String, content: String): Boolean {
        val launch = AndroidRecoveryExport.launcher ?: return false
        val ctx = AndroidRecoveryExport.appContext ?: return false

        return suspendCancellableCoroutine { cont ->
            launch(fileName) { uri ->
                if (uri == null) {
                    if (cont.isActive) cont.resume(false)
                    return@launch
                }
                val ok = runCatching {
                    ctx.contentResolver.openOutputStream(uri)?.use { it.write(content.toByteArray()) }
                    true
                }.getOrElse {
                    Log.w(AndroidRecoveryExport.TAG, "写文件失败", it)
                    false
                }
                if (cont.isActive) cont.resume(ok)
            }
        }
    }

    override suspend fun print(content: String): Boolean {
        val ctx = AndroidRecoveryExport.appContext ?: return false
        return runCatching {
            val pm = ctx.getSystemService(Context.PRINT_SERVICE) as PrintManager
            pm.print("雁书恢复码", TextPrintAdapter("雁书恢复码", content), null)
            true
        }.getOrElse {
            Log.w(AndroidRecoveryExport.TAG, "调起打印失败", it)
            false
        }
    }
}

/**
 * 把纯文本画成 PDF 交给系统打印框架。
 *
 * 为什么不用 WebView 渲染：为十行文本起一个 WebView 太重，还要额外管它的生命周期。
 */
private class TextPrintAdapter(
    private val jobName: String,
    private val content: String,
) : PrintDocumentAdapter() {

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes,
        cancellationSignal: CancellationSignal?,
        callback: LayoutResultCallback,
        extras: Bundle?,
    ) {
        if (cancellationSignal?.isCanceled == true) {
            callback.onLayoutCancelled()
            return
        }
        val info = PrintDocumentInfo.Builder(jobName)
            .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
            .setPageCount(PrintDocumentInfo.PAGE_COUNT_UNKNOWN)
            .build()
        callback.onLayoutFinished(info, true)
    }

    override fun onWrite(
        pages: Array<out PageRange>?,
        destination: ParcelFileDescriptor,
        cancellationSignal: CancellationSignal?,
        callback: WriteResultCallback,
    ) {
        try {
            val doc = PdfDocument()
            // A4 @72dpi：595 x 842 点是打印框架的约定尺寸
            val info = PdfDocument.PageInfo.Builder(595, 842, 1).create()
            val page = doc.startPage(info)
            val paint = TextPaint().apply {
                textSize = 11f
                isAntiAlias = true
            }
            val layout = StaticLayout.Builder
                .obtain(content, 0, content.length, paint, 545)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .build()
            page.canvas.save()
            page.canvas.translate(25f, 40f)
            layout.draw(page.canvas)
            page.canvas.restore()
            doc.finishPage(page)

            FileOutputStream(destination.fileDescriptor).use { out -> doc.writeTo(out) }
            doc.close()
            callback.onWriteFinished(arrayOf(PageRange.ALL_PAGES))
        } catch (t: Throwable) {
            Log.w(AndroidRecoveryExport.TAG, "生成 PDF 失败", t)
            callback.onWriteFailed(t.message)
        }
    }
}

actual fun recoveryExport(): RecoveryExport = AndroidExport
