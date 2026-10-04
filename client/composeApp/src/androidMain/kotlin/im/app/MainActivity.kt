package im.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
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
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    AppRoot()
                }
            }
        }
    }

    // App 在后台时浏览器回跳会走这里（manifest 里 MainActivity 设为 singleTask）
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        AndroidOidc.onUri(intent.data)
    }
}
