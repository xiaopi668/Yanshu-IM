package im.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import org.jetbrains.compose.resources.Font
import im.app.resources.Res
import im.app.resources.yanshu_cjk

@Composable
actual fun appFontFamily(): FontFamily? = FontFamily(Font(Res.font.yanshu_cjk))
