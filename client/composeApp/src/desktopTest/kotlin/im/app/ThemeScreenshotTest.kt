package im.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * 把界面离屏渲染成 PNG。
 *
 * 为什么不在真窗口里截图：桌面窗口尺寸/缩放受窗口管理器影响（HiDPI + XWayland 下
 * resize 事件还会丢），截出来的图不可复现。ImageComposeScene 直接跑 Compose 的
 * 布局与绘制，同样的输入永远得到同样的像素。
 *
 * 产物在 composeApp/build/reports/screenshots/ 下（不提交进仓库）。
 */
class ThemeScreenshotTest {

    @OptIn(ExperimentalMaterial3Api::class)
    private fun gallery(dark: Boolean) = @androidx.compose.runtime.Composable {
        YanshuTheme(dark = dark) {
            Surface(color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    // M3 TopAppBar
                    Surface(shape = MaterialTheme.shapes.large) {
                        TopAppBar(
                            title = {
                                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                    Avatar("阿波", size = 34.dp, round = true)
                                    Spacer(Modifier.width(10.dp))
                                    Column {
                                        Text("阿波", style = MaterialTheme.typography.titleMedium)
                                        Text(
                                            "单聊",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            },
                            actions = { IconButton(onClick = {}) { Text("📞") } },
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            ),
                        )
                    }

                    Spacer(Modifier.height(12.dp))
                    // M3 SegmentedButton（三个视图切换）
                    SegmentedRow(
                        options = listOf("chat" to "聊天", "contacts" to "通讯录", "moments" to "朋友圈"),
                        selected = "chat",
                        onSelect = {},
                    )

                    Spacer(Modifier.height(8.dp))
                    // M3 ListItem 列表
                    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                        Column {
                            listOf(
                                Triple("产品讨论组", "群聊 · 4 人", 11),
                                Triple("阿波", "单聊", 4),
                                Triple("卡萝", "单聊", 3),
                            ).forEachIndexed { i, (title, sub, unread) ->
                                ListItem(
                                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                    leadingContent = { Avatar(title, size = 40.dp, round = true) },
                                    headlineContent = { Text(title, style = MaterialTheme.typography.bodyLarge) },
                                    supportingContent = {
                                        Text(sub, style = MaterialTheme.typography.labelSmall)
                                    },
                                    trailingContent = {
                                        if (unread > 0) {
                                            Box(
                                                Modifier
                                                    .clip(CircleShape)
                                                    .background(MaterialTheme.colorScheme.error)
                                                    .padding(horizontal = 7.dp, vertical = 2.dp),
                                            ) {
                                                Text(
                                                    unread.toString(),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onError,
                                                )
                                            }
                                        }
                                    },
                                )
                                if (i < 2) {
                                    Box(
                                        Modifier.fillMaxWidth().height(1.dp)
                                            .background(MaterialTheme.colorScheme.outlineVariant),
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    // 消息气泡：自己的用 primaryContainer，对方的用描边
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer)
                                .padding(horizontal = 14.dp, vertical = 9.dp),
                        ) {
                            Text("好，那就八点半，我把接口文档先发群里",
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                                .padding(horizontal = 14.dp, vertical = 9.dp),
                        ) {
                            Text("收到 👍", style = MaterialTheme.typography.bodyMedium)
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    // 输入栏：M3 filled TextField（pill）+ FilledIconButton
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        IconButton(onClick = {}) { Text("📎") }
                        Spacer(Modifier.width(4.dp))
                        TextField(
                            value = "",
                            onValueChange = {},
                            placeholder = { Text("输入消息…") },
                            modifier = Modifier.weight(1f),
                            shape = MaterialTheme.shapes.extraLarge,
                            colors = TextFieldDefaults.colors(
                                focusedIndicatorColor = Color.Transparent,
                                unfocusedIndicatorColor = Color.Transparent,
                                disabledIndicatorColor = Color.Transparent,
                            ),
                        )
                        Spacer(Modifier.width(8.dp))
                        FilledIconButton(onClick = {}, modifier = Modifier.size(44.dp)) {
                            Text("➤")
                        }
                    }
                }
            }
        }
    }

    private fun render(name: String, dark: Boolean) {
        val scene = ImageComposeScene(width = 900, height = 780, density = Density(1f)) {
            gallery(dark)()
        }
        val image = scene.render()
        val bytes = image.encodeToData()!!.bytes
        assertTrue(bytes.isNotEmpty(), "渲染结果不应为空")

        val dir = File("build/reports/screenshots").apply { mkdirs() }
        File(dir, name).writeBytes(bytes)
        println("截图已生成: " + File(dir, name).absolutePath + " (" + bytes.size + " B)")
        scene.close()
    }

    @Test
    fun renderDark() = render("m3-gallery-dark.png", dark = true)

    @Test
    fun renderLight() = render("m3-gallery-light.png", dark = false)
}
