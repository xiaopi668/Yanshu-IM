package im.client.totp

import java.awt.FileDialog
import java.awt.Frame
import java.awt.print.PrinterException
import java.awt.print.PrinterJob
import java.io.File
import java.nio.file.Files
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * 桌面端导出。
 *
 * 保存用 AWT FileDialog（原生对话框，比 JFileChooser 更像系统行为）；
 * 打印走 java.awt.print.PrinterJob —— 直接把文本画到页面上，不依赖外部程序。
 */
private object DesktopRecoveryExport : RecoveryExport {
    override val canSave: Boolean = true
    override val canPrint: Boolean = true

    override suspend fun save(fileName: String, content: String): Boolean =
        suspendCancellableCoroutine { cont ->
            // 对话框必须在 EDT 上弹，否则在 Linux 上可能直接卡住或画不出来
            SwingUtilities.invokeLater {
                try {
                    val dialog = FileDialog(null as Frame?, "保存恢复码", FileDialog.SAVE)
                    dialog.file = fileName
                    dialog.isVisible = true
                    val dir = dialog.directory
                    val name = dialog.file
                    if (dir == null || name == null) {
                        cont.resume(false)
                        return@invokeLater
                    }
                    val target = File(dir, if (name.contains('.')) name else "$name.txt")
                    target.writeText(content)
                    cont.resume(true)
                } catch (t: Throwable) {
                    cont.resume(false)
                }
            }
        }

    override suspend fun print(content: String): Boolean {
        // PrinterJob.print() 会阻塞到打印对话框关闭；放到后台线程，避免卡住 UI
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val job = PrinterJob.getPrinterJob()
                job.setPrintable { graphics, pageFormat, pageIndex ->
                    if (pageIndex > 0) return@setPrintable java.awt.print.Printable.NO_SUCH_PAGE

                    val g = graphics as java.awt.Graphics2D
                    g.translate(pageFormat.imageableX.toInt(), pageFormat.imageableY.toInt())
                    // 先设字体再取 fontMetrics：否则行高按默认字体算，行距会挤在一起
                    g.font = java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 11)
                    val lineHeight = g.fontMetrics.height
                    val maxY = pageFormat.imageableHeight.toInt()
                    var y = lineHeight
                    for (line in content.split("\n")) {
                        if (y > maxY) break
                        g.drawString(line, 4, y)
                        y += lineHeight
                    }
                    java.awt.print.Printable.PAGE_EXISTS
                }
                if (!job.printDialog()) return@withContext false
                job.print()
                true
            } catch (e: PrinterException) {
                false
            } catch (t: Throwable) {
                false
            }
        }
    }
}

actual fun recoveryExport(): RecoveryExport = DesktopRecoveryExport