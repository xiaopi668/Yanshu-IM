package im.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import im.client.net.ConnState

// ---------- 通用小部件：头像 / 状态徽标 / 分段切换 ----------

private const val AVATAR_ROLES = 5

/** 按名字取稳定的角色下标，同一个昵称每次进来颜色都一样 */
private fun avatarRoleIndex(seed: String): Int {
    var h = 0
    for (ch in seed) h = 31 * h + ch.code
    return ((h % AVATAR_ROLES) + AVATAR_ROLES) % AVATAR_ROLES
}

/**
 * 头像底色取自当前配色方案的 container 角色（Material You 的做法：
 * 头像不自己发明颜色，而是从主题色调里取 —— 深浅色模式与动态取色都会自动跟随）。
 * 返回「底色 to 文字色」。
 */
@Composable
private fun avatarColorsFor(seed: String): Pair<Color, Color> {
    val s = MaterialTheme.colorScheme
    return when (avatarRoleIndex(seed)) {
        0 -> s.primaryContainer to s.onPrimaryContainer
        1 -> s.secondaryContainer to s.onSecondaryContainer
        2 -> s.tertiaryContainer to s.onTertiaryContainer
        3 -> s.errorContainer to s.onErrorContainer
        else -> s.surfaceContainerHighest to s.onSurface
    }
}

/** 首字母头像：没有头像图时用昵称首字 + 稳定色 */
@Composable
fun Avatar(name: String, size: Dp = 40.dp, round: Boolean = false, modifier: Modifier = Modifier) {
    val fontSize = with(LocalDensity.current) { (size.value * 0.38f).toSp() }
    val (bg, fg) = avatarColorsFor(name)
    Box(
        modifier
            .size(size)
            // M3 容器形状：圆形或 large 档圆角
            .clip(if (round) CircleShape else MaterialTheme.shapes.large)
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.trim().take(1).ifEmpty { "?" }.uppercase(),
            color = fg,
            fontSize = fontSize,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** 连接状态小圆点 + 文案 */
@Composable
fun StatusPill(state: ConnState, modifier: Modifier = Modifier) {
    val (label, color) = when (state) {
        is ConnState.Authenticated -> "在线" to Color(0xFF16A34A)
        is ConnState.Connecting -> "连接中" to Color(0xFFF59E0B)
        else -> "离线" to Color(0xFF94A3B8)
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * 顶层切换（聊天 / 通讯录 / 朋友圈）。
 *
 * 用 M3 的 PrimaryTabRow + Tab：选中态由「滑块指示器」表达，这是 Material 3 的标志性控件。
 * 之前是自己画的一条底 + 一个白色方块 —— 看着像，但拿不到 M3 的指示器动画、
 * 无障碍语义、以及 RTL/尺寸适配。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SegmentedRow(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    val index = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    PrimaryTabRow(
        selectedTabIndex = index,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.primary,
        modifier = Modifier.fillMaxWidth(),
    ) {
        options.forEach { (id, label) ->
            Tab(
                selected = selected == id,
                onClick = { onSelect(id) },
                text = {
                    Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                },
            )
        }
    }
}

/** 空状态 */
@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
