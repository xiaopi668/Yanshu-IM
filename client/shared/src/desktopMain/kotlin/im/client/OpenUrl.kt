package im.client

import java.awt.Desktop

actual fun openUrl(url: String) {
    try {
        if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(java.net.URI(url))
    } catch (_: Throwable) {
    }
}
