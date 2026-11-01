package im.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import im.client.auth.AndroidOidc
import im.client.db.appContext
import im.client.file.AndroidFilePicker

class MainActivity : ComponentActivity() {
    // launcher 必须在 onCreate 之前注册（字段初始化阶段即可），交给 shared 层调用
    private val pickFileLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            AndroidFilePicker.onResult(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // SQLDelight 的 AndroidSqliteDriver 需要 Context，先给 shared 层赋值（应用级，避免 Activity 泄漏）
        appContext = applicationContext
        // 选文件：把 launcher 与应用级 resolver 交给 shared 层
        //（shared 拿不到 Activity，这是唯一的接入点；不注入时 pickFile() 返回 null）
        AndroidFilePicker.launch = { mimes -> pickFileLauncher.launch(mimes) }
        AndroidFilePicker.resolver = contentResolver
        // OIDC 回调：App 被回收后由 yanshu:// deep link 冷启动，令牌就在这个 Intent 里
        AndroidOidc.onUri(intent?.data)
        // Material 3 要求 edge-to-edge：内容画到系统栏后面，由 Scaffold 的
        // contentWindowInsets 统一留出安全区。不调用的话，targetSdk 35+ 上系统仍会
        // 强制 edge-to-edge，但系统栏图标的明暗不会跟随深浅色 —— 深色模式下会看不见。
        enableEdgeToEdge()
        setContent {
            // 原写法：再包一层 MaterialTheme + Surface(默认色)。
            // 那层用的是 M3 基线配色（紫色），在 AppRoot 的 YanshuTheme 生效前会闪一下；
            // 现在由 AppRoot 自己提供主题与底色，宿主只负责装载。
            AppRoot()
        }
    }

    // App 在后台时浏览器回跳会走这里（manifest 里 MainActivity 设为 singleTask）
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        AndroidOidc.onUri(intent.data)
    }
}
