package im.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Font
import im.app.resources.Res
import im.app.resources.yanshu_cjk
import org.jetbrains.compose.resources.getFontResourceBytes
import org.jetbrains.compose.resources.rememberResourceEnvironment

// 进程内缓存：字体只加载一次
private var cachedFamily: FontFamily? = null
private var loadFinished = false

@Composable
actual fun appFontState(): AppFontState {
    val env = rememberResourceEnvironment()
    var state by remember {
        mutableStateOf<AppFontState>(
            if (loadFinished) AppFontState.Ready(cachedFamily) else AppFontState.Loading
        )
    }
    LaunchedEffect(env) {
        if (!loadFinished) {
            cachedFamily = runCatching {
                // getFontResourceBytes 是挂起函数：真正等待字体资源读完
                val bytes = getFontResourceBytes(env, Res.font.yanshu_cjk)
                // skiko 目标通用：从字节直接构造 Font（不经过异步的 Font(resource)）
                FontFamily(Font("yanshu-cjk", bytes))
            }.getOrNull()
            loadFinished = true
        }
        state = AppFontState.Ready(cachedFamily)
    }
    return state
}
