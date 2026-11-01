package im.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import im.client.net.ConnState

// ---------- 通用小部件：头像 / 状态徽标 / 分段切换 ----------

private const val AVATAR_ROLES = 9

/** 按名字取稳定的角色下标，同一个昵称每次进来颜色都一样 */
private fun avatarRoleIndex(seed: String): Int {
    var h = 0
    for (ch in seed) h = 31 * h + ch.code
    return ((h % AVATAR_ROLES) + AVATAR_ROLES) % AVATAR_ROLES
}

/**
 * 头像配色：优先用主题提供的「调和色板」（从种子派生的 9 个容器色），
 * 色板缺失时（理论上不会发生）回落到当前的 container 角色。
 */
@Composable
private fun avatarColorsFor(seed: String): Pair<Color, Color> {
    val palette = LocalAvatarPalette.current
    if (palette.isNotEmpty()) return palette[avatarRoleIndex(seed) % palette.size]
    val s = MaterialTheme.colorScheme
    return when (avatarRoleIndex(seed)) {
        0 -> s.primaryContainer to s.onPrimaryContainer
        1 -> s.secondaryContainer to s.onSecondaryContainer
        else -> s.tertiaryContainer to s.onTertiaryContainer
    }
}

// 原写法：Box + clip(RoundedCornerShape) + background(bg) 自绘一个圆形/圆角色块。
// 现在：M3 Surface 提供形状与底色（Surface 自己会裁剪内容），不再手写 clip/background。
@Composable
fun Avatar(name: String, size: Dp = 40.dp, round: Boolean = false, modifier: Modifier = Modifier) {
    val fontSize = with(LocalDensity.current) { (size.value * 0.38f).toSp() }
    val (bg, fg) = avatarColorsFor(name)
    Surface(
        modifier = modifier.size(size),
        shape = if (round) CircleShape else MaterialTheme.shapes.large,
        color = bg,
        contentColor = fg,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                name.trim().take(1).ifEmpty { "?" }.uppercase(),
                fontSize = fontSize,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

// 原写法：三个硬编码色值 Color(0xFF16A34A/0xFFF59E0B/0xFF94A3B8) + Box 画圆点。
// 现在：圆点交给 Surface，颜色改用语义角色（在线=primary、连接中=tertiary、离线=outline）。
// 状态同时有文字标签，不靠颜色单独传达 ✅
@Composable
fun StatusPill(state: ConnState, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val (label, color) = when (state) {
        is ConnState.Authenticated -> "在线" to scheme.primary
        is ConnState.Connecting -> "连接中" to scheme.tertiary
        else -> "离线" to scheme.outline
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Surface(modifier = Modifier.size(8.dp), shape = CircleShape, color = color) {}
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelSmall, color = scheme.onSurfaceVariant)
    }
}

/**
 * 顶层切换（聊天 / 通讯录 / 朋友圈）。
 *
 * 用 M3 的 SingleChoiceSegmentedButtonRow + SegmentedButton（药丸形连体按钮组）。
 * 原写法是自绘「一条底 + 选中方块」：看着像，但拿不到涟漪、指示器动画与无障碍语义，
 * 未选中段还会出现整块方角 hover 底色。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SegmentedRow(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    // M3 的 SegmentedButton：一组连在一起的药丸按钮，选中段用 tonal 底色。
    // 比 PrimaryTabRow 更贴合这个场景 —— TabRow 的指示器是一条方头细线，
    // 未选中段还会有整块方角 hover 底色，和 Material You 的圆润观感不搭。
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
        space = 0.dp,
    ) {
        options.forEachIndexed { i, (id, label) ->
            SegmentedButton(
                selected = selected == id,
                onClick = { onSelect(id) },
                shape = SegmentedButtonDefaults.itemShape(index = i, count = options.size),
                // 不要勾选图标：只有三个短标签，纯文字更紧凑
                icon = {},
                label = {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                    )
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
