package im.app

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document
import kotlin.js.ExperimentalJsExport
import kotlin.js.JsExport

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    applyWebDefaults()
    ComposeViewport(document.getElementById("composeTarget")!!) {
        AppRoot()
    }
}

// wasm 加载器显式调用入口（CMP wasm 发行包不会自动执行 main）
@OptIn(ExperimentalJsExport::class, ExperimentalComposeUiApi::class)
@JsExport
@Suppress("NON_EXPORTABLE_TYPE")
fun startImApp() {
    main()
}
