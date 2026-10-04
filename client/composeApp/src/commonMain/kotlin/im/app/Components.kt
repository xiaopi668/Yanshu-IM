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

private val avatarPalette = listOf(
    Color(0xFF2563EB),
    Color(0xFF0F766E),
    Color(0xFF7C3AED),
    Color(0xFFDB2777),
    Color(0xFFEA580C),
    Color(0xFF0891B2),
    Color(0xFF4F46E5),
    Color(0xFF65A30D),
)

/** 按名字取稳定色，同一个昵称每次进来颜色都一样 */
fun avatarColor(seed: String): Color {
    var h = 0
    for (ch in seed) h = 31 * h + ch.code
    val i = ((h % avatarPalette.size) + avatarPalette.size) % avatarPalette.size
    return avatarPalette[i]
}

/** 首字母头像：没有头像图时用昵称首字 + 稳定色 */
@Composable
fun Avatar(name: String, size: Dp = 40.dp, round: Boolean = false, modifier: Modifier = Modifier) {
    val fontSize = with(LocalDensity.current) { (size.value * 0.38f).toSp() }
    Box(
        modifier
            .size(size)
            .clip(if (round) CircleShape else RoundedCornerShape((size.value * 0.30f).dp))
            .background(avatarColor(name)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.trim().take(1).ifEmpty { "?" }.uppercase(),
            color = Color.White,
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
 * 分段切换（聊天 / 通讯录 / 朋友圈）。
 * 比三个 FilterChip 更贴近「现代简约」：一条底 + 选中块，没有多余描边。
 */
@Composable
fun SegmentedRow(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f))
            .padding(4.dp),
    ) {
        options.forEach { (id, label) ->
            val active = selected == id
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (active) MaterialTheme.colorScheme.surface else Color.Transparent)
                    .clickable { onSelect(id) }
                    .padding(vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (active) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
