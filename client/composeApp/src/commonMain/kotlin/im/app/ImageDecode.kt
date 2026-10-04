package im.app

import androidx.compose.ui.graphics.ImageBitmap

/**
 * 把图片字节解码成 ImageBitmap。
 *
 * 为什么要 expect/actual：原先这段直接写在 commonMain 里用
 * `org.jetbrains.skia.Image.makeFromEncoded(...)` —— skiko 只在 desktop 与 wasm 上存在，
 * Android 没有，于是 **Android 目标根本编译不过**。
 *
 * 失败返回 null，调用方显示占位图，不抛异常。
 */
expect fun decodeImageBitmap(bytes: ByteArray): ImageBitmap?
