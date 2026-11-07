package im.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import im.client.ImClient
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import im.client.totp.recoveryCodesAsText
import im.client.totp.recoveryExport
import androidx.compose.ui.text.style.TextOverflow
import im.client.api.ContactRequestResp
import im.client.api.ContactResp
import im.client.api.MomentResp
import im.client.openUrl
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ============ 通讯录 ============

@Composable
fun ContactsView(client: ImClient, onOpenChat: (peerUid: String) -> Unit) {
    var contacts by remember { mutableStateOf<List<ContactResp>>(emptyList()) }
    var requests by remember { mutableStateOf<List<ContactRequestResp>>(emptyList()) }
    var yidInput by remember { mutableStateOf("") }
    var msgInput by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun reload() {
        scope.launch {
            try {
                contacts = client.api.contacts(client.myToken)
                requests = client.api.contactRequests(client.myToken)
            } catch (e: Throwable) {
                error = e.message
            }
        }
    }
    LaunchedEffect(Unit) { reload() }
    // 实时事件：收到申请/通过 → 刷新
    LaunchedEffect(Unit) {
        client.connection.contactEvents.collect { reload() }
    }

    Column(Modifier.fillMaxSize()) {
        // 按雁书号加好友
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                yidInput, { yidInput = it },
                placeholder = { Text("输入雁书号") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    scope.launch {
                        try {
                            val u = client.api.searchByYid(client.myToken, yidInput.trim())
                            client.api.requestContact(client.myToken, u.uid, msgInput)
                            yidInput = ""; msgInput = ""; error = null
                        } catch (e: Throwable) {
                            error = e.message ?: e.toString()
                        }
                    }
                },
                enabled = yidInput.isNotBlank(),
            ) { Text("添加") }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp)) }

        LazyColumn(Modifier.weight(1f)) {
            // 好友申请
            if (requests.any { it.status == "pending" }) {
                item {
                    Text(
                        "新的朋友",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
                    )
                }
                items(requests.filter { it.status == "pending" }, key = { it.id }) { req ->
                    Card(
                        Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                    ) {
                        Column(Modifier.padding(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Avatar(req.nickname.ifEmpty { req.yid }, size = 36.dp, round = true)
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(
                                        req.nickname.ifEmpty { req.yid },
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        req.yid,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (req.message.isNotBlank()) {
                                Text(
                                    req.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 8.dp),
                                )
                            }
                            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = {
                                    scope.launch { client.api.acceptContact(client.myToken, req.id); reload() }
                                }) { Text("接受") }
                                OutlinedButton(onClick = {
                                    scope.launch { client.api.rejectContact(client.myToken, req.id); reload() }
                                }) { Text("拒绝") }
                            }
                        }
                    }
                }
            }
            // 通讯录列表
            item {
                Text(
                    "通讯录 (${contacts.size})",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
                )
            }
            if (contacts.isEmpty()) {
                item { EmptyHint("通讯录还是空的，用上面的雁书号加个好友吧") }
            }
            items(contacts, key = { it.uid }) { c ->
                var menuOpen by remember { mutableStateOf(false) }
                var editRemark by remember { mutableStateOf(false) }
                var confirmDelete by remember { mutableStateOf(false) }
                val name = c.remark.ifEmpty { c.nickname }
                // 原写法：Row + Modifier.clickable（无涟漪、无按钮语义）。
                // 现在：Surface 的 onClick 重载，涟漪/焦点/语义都由 M3 提供。
                Surface(
                    onClick = { onOpenChat(c.uid) },
                    modifier = Modifier.fillMaxWidth(),
                    color = Color.Transparent,
                ) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(name, size = 40.dp, round = true)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            name,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "雁书号 ${c.yid.ifEmpty { "-" }}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Box {
                        TextButton(onClick = { menuOpen = true }) { Text("⋯") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("设置备注") },
                                onClick = { menuOpen = false; editRemark = true },
                            )
                            DropdownMenuItem(
                                text = { Text("删除好友") },
                                onClick = { menuOpen = false; confirmDelete = true },
                            )
                        }
                    }
                }
                }
                if (editRemark) {
                    var remark by remember { mutableStateOf(c.remark) }
                    AlertDialog(
                        onDismissRequest = { editRemark = false },
                        title = { Text("设置备注") },
                        text = { OutlinedTextField(remark, { remark = it }, singleLine = true) },
                        confirmButton = {
                            TextButton(onClick = {
                                scope.launch {
                                    client.api.setRemark(client.myToken, c.uid, remark)
                                    editRemark = false
                                    reload()
                                    // 会话列表里的标题由服务端按备注重算，必须重新拉一次，
                                    // 否则聊天页标题还是旧昵称（真实反馈：改备注不生效）
                                    runCatching { client.refreshConversations() }
                                }
                            }) { Text("保存") }
                        },
                        dismissButton = { TextButton(onClick = { editRemark = false }) { Text("取消") } },
                    )
                }
                if (confirmDelete) {
                    AlertDialog(
                        onDismissRequest = { confirmDelete = false },
                        title = { Text("删除好友") },
                        text = { Text("确定删除 ${c.remark.ifEmpty { c.nickname }} 吗？") },
                        confirmButton = {
                            TextButton(onClick = {
                                scope.launch { client.api.removeContact(client.myToken, c.uid); confirmDelete = false; reload() }
                            }) { Text("删除") }
                        },
                        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } },
                    )
                }
            }
        }
    }
}

// ============ 朋友圈 ============

@Composable
fun MomentsView(client: ImClient) {
    var feed by remember { mutableStateOf<List<im.client.api.MomentResp>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var showComposer by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun reload() {
        scope.launch {
            try {
                feed = client.api.momentFeed(client.myToken, "")
            } catch (e: Throwable) {
                error = e.message
            }
        }
    }
    LaunchedEffect(Unit) { reload() }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp)) {
            items(feed, key = { it.id }) { m ->
                MomentCard(
                    client = client,
                    moment = m,
                    onChanged = { reload() },
                )
                Spacer(Modifier.height(8.dp))
            }
            if (feed.isEmpty()) {
                item {
                    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                        Text("还没有动态，点击右下角发布", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (error != null) {
            Text(
                error!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.BottomStart).padding(8.dp),
            )
        }
        // 发动态按钮
        ExtendedFloatingActionButton(
            onClick = { showComposer = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp),
        ) { Text("＋ 动态") }
    }

    if (showComposer) {
        MomentComposer(client) { showComposer = false; reload() }
    }
}

@Composable
fun MomentCard(client: ImClient, moment: im.client.api.MomentResp, onChanged: () -> Unit) {
    val scope = rememberCoroutineScope()
    var commentInput by remember { mutableStateOf("") }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(moment.nickname, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (moment.uid == client.myUid) {
                    var del by remember { mutableStateOf(false) }
                    TextButton(onClick = { del = true }, contentPadding = PaddingValues(0.dp)) { Text("删除", style = MaterialTheme.typography.labelSmall) }
                    if (del) {
                        AlertDialog(
                            onDismissRequest = { del = false },
                            title = { Text("删除动态") },
                            text = { Text("确定删除这条动态吗？") },
                            confirmButton = { TextButton(onClick = {
                                scope.launch { client.api.deleteMoment(client.myToken, moment.id); del = false; onChanged() }
                            }) { Text("删除") } },
                            dismissButton = { TextButton(onClick = { del = false }) { Text("取消") } },
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            if (moment.text.isNotBlank()) {
                Text(moment.text, style = MaterialTheme.typography.bodyMedium)
            }
            // 图片九宫格（MVP：等比小图行，最多 3 列）
            val momentImages: List<String>? = moment.images
            if (!momentImages.isNullOrEmpty()) {
                Spacer(Modifier.height(8.dp))
                val cells = momentImages.take(9)
                Column {
                    cells.chunked(3).forEach { rowImages ->
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            rowImages.forEach { key ->
                                // 原写法：把 clip + background 挂在图片自己的 Modifier 上。
                                // 现在：外层 Surface 负责形状与占位底色，图片只填满它。
                                Surface(
                                    modifier = Modifier.size(96.dp),
                                    shape = MaterialTheme.shapes.small,
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                ) {
                                    NetImage(
                                        api = client.api,
                                        token = client.myToken,
                                        key = key,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    momentTimeOf(moment.created_at),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                // 评论列表已包含在 moment.comments
            }
            // 点赞/评论操作行
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                TextButton(onClick = {
                    scope.launch {
                        try {
                            if (moment.liked_by_me) client.api.unlikeMoment(client.myToken, moment.id)
                            else client.api.likeMoment(client.myToken, moment.id)
                            onChanged()
                        } catch (_: Throwable) {}
                    }
                }) { Text(if (moment.liked_by_me) "❤ ${moment.likes}" else "♡ ${moment.likes}") }
                Spacer(Modifier.weight(1f))
            }
            if (moment.comments.isNotEmpty()) {
                Column(Modifier.padding(top = 4.dp)) {
                    moment.comments.forEach { c ->
                        Text(
                            "${c.nickname}: ${c.text}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                }
            }
            // 评论输入
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                OutlinedTextField(
                    commentInput, { commentInput = it },
                    placeholder = { Text("评论…", style = MaterialTheme.typography.labelSmall) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    enabled = commentInput.isNotBlank(),
                    onClick = {
                        val text = commentInput
                        commentInput = ""
                        scope.launch {
                            try {
                                client.api.commentMoment(client.myToken, moment.id, text)
                                onChanged()
                            } catch (_: Throwable) {}
                        }
                    },
                ) { Text("发送") }
            }
        }
    }
}

@Composable
fun MomentComposer(client: ImClient, onDone: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var keys by remember { mutableStateOf(listOf<String>()) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDone,
        title = { Text("发动态") },
        text = {
            Column {
                OutlinedTextField(text, { text = it }, placeholder = { Text("这一刻的想法…") }, minLines = 3)
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        enabled = !busy && keys.size < 9,
                        onClick = {
                            scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                                try {
                                    busy = true
                                    val f = im.client.file.pickFile() ?: return@launch
                                    val kind = if (f.mime.startsWith("image/")) "image" else "file"
                                    val key = client.api.uploadAttachment(client.myToken, kind, f.bytes)
                                    keys = keys + key
                                } catch (e: Throwable) {
                                    error = e.message
                                }
                                busy = false
                            }
                        },
                    ) { Text("添加图片 (${keys.size})") }
                    Spacer(Modifier.weight(1f))
                    if (busy) CircularProgressIndicator(Modifier.size(20.dp))
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && (text.isNotBlank() || keys.isNotEmpty()),
                onClick = {
                    scope.launch {
                        try {
                            busy = true
                            client.api.createMoment(client.myToken, text, keys)
                            onDone()
                        } catch (e: Throwable) {
                            error = e.message
                        }
                        busy = false
                    }
                },
            ) { Text("发布") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("取消") } },
    )
}

// 简易网络图片（字节加载 → ImageBitmap）。
// key 是对象存储里的 key；地址在渲染时用调用方自己的登录态换短时票据得到，
// 因此登录 JWT 不会出现在任何会被保存/分享的 URL 里。
@Composable
fun NetImage(
    api: im.client.api.Api,
    token: String,
    key: String,
    modifier: Modifier = Modifier,
    contentScale: androidx.compose.ui.layout.ContentScale = androidx.compose.ui.layout.ContentScale.Crop,
) {
    var bmp by remember(key) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var failed by remember(key) { mutableStateOf(false) }
    LaunchedEffect(key) {
        try {
            val url = api.attachmentUrl(token, key)
            val (code, data) = im.client.api.Http.getBinary(url)
            if (code in 200..299) {
                // 解码走各平台实现（Android 用 BitmapFactory，desktop/wasm 用 skiko）
                bmp = decodeImageBitmap(data)
                if (bmp == null) failed = true
            } else {
                failed = true
            }
        } catch (_: Throwable) {
            failed = true
        }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) {
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.graphics.painter.BitmapPainter(b),
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (failed) {
            Text("🖼", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            CircularProgressIndicator(Modifier.size(18.dp))
        }
    }
}

/** 通讯录里点好友：打开（或创建）单聊会话并切回聊天视图 */
internal fun scopeLaunchOpenSingle(client: ImClient, peerUid: String, onDone: (im.client.store.Conversation) -> Unit) {
    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()).launch {
        try {
            val convId = client.openSingle(peerUid)
            client.refreshConversations()
            val conv = client.conversations.value.firstOrNull { it.id == convId }
            if (conv != null) onDone(conv)
        } catch (_: Throwable) {}
    }
}


internal fun momentTimeOf(ms: Long): String = im.client.formatChatTime(ms)

// ============ 个人资料 ============

// 与服务端 yidRe 保持一致：^[a-zA-Z][a-zA-Z0-9_-]{4,19}$
private val yidRegex = Regex("^[a-zA-Z][a-zA-Z0-9_-]{4,19}$")

@Composable
fun ProfileDialog(
    client: ImClient,
    onDismiss: () -> Unit,
    accentSeed: String? = null,
    onAccentChange: (String?) -> Unit = {},
) {
    var nickname by remember { mutableStateOf(client.myNickname) }
    var yid by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var yidChanged by remember { mutableStateOf(true) }
    // 服务端返回的原始雁书号：只有用户真的改了才提交 changeYid。
    // 之前无条件提交，导致「雁书号不合规」的账号连昵称都存不了
    //（早期 OIDC 自动建号生成的号有 26 位，超过 20 位上限）。
    var originalYid by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    // 账号安全子对话框
    var showPasswordDialog by remember { mutableStateOf(false) }
    var showEmailDialog by remember { mutableStateOf(false) }
    // 两步验证
    var totpEnabled by remember { mutableStateOf(false) }
    var totpRecoveryLeft by remember { mutableStateOf(0) }
    var showTotpDialog by remember { mutableStateOf(false) }
    // 通行密钥
    var passkeys by remember { mutableStateOf<List<im.client.api.PasskeyInfo>>(emptyList()) }
    val passkeyOk = remember { runCatching { im.client.passkey.passkeyPlatform().supported }.getOrDefault(false) }

    var avatarKey by remember { mutableStateOf("") }
    // 通行密钥绑定/删除是异步的，按钮需要 loading 态（避免连点重复注册）
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        try {
            val me = client.api.me(client.myToken)
            nickname = me.nickname
            yid = me.yid
            originalYid = me.yid
            username = me.username
            yidChanged = me.yid_changed
            avatarKey = me.avatar
            runCatching {
                val st = client.api.totpStatus(client.myToken)
                totpEnabled = st.enabled
                totpRecoveryLeft = st.recovery_left
            }
            runCatching { passkeys = client.api.passkeyList(client.myToken) }
        } catch (_: Throwable) {}
    }
    val avatarKeyOrNull = avatarKey.ifBlank { null }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("我的资料") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                // 头像：原写法用 clip(RoundedCornerShape(32.dp)) + background 自绘圆形；
                // 现在统一交给 Surface（圆形由 CircleShape 表达，底色由 color 提供）。
                Surface(
                    modifier = Modifier.size(64.dp),
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    if (avatarKeyOrNull != null) {
                        NetImage(
                            api = client.api,
                            token = client.myToken,
                            key = avatarKeyOrNull,
                            modifier = Modifier.fillMaxSize(),
                        )
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(nickname.take(1), style = MaterialTheme.typography.headlineSmall)
                        }
                    }
                }
                TextButton(onClick = {
                    scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
                        try {
                            val f = im.client.file.pickFile() ?: return@launch
                            val key = client.api.uploadAttachment(client.myToken, "image", f.bytes)
                            client.api.setAvatar(client.myToken, key)
                            avatarKey = key
                        } catch (e: Throwable) { msg = e.message }
                    }
                }) { Text("更换头像") }
                Spacer(Modifier.height(8.dp))
                // 昵称 / 用户名 / 雁书号 三个都可编辑（雁书号只放一次，由服务端 yid_changed 把关）
                OutlinedTextField(
                    nickname, { nickname = it },
                    label = { Text("昵称") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    username, { username = it },
                    label = { Text("用户名（登录用）") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                if (!yidChanged) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        yid, { yid = it },
                        label = { Text("雁书号") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        if (!yidRegex.matches(yid)) {
                            // 早期 OIDC 自动建号生成的雁书号不满足规则（超长），
                            // 不说清楚的话用户只会看到「保存失败」。
                            "当前雁书号不符合规范（需 5-20 位、字母开头）。这是早期 OIDC 自动建号留下的，改成合规的即可，且不计入" +
                                "「只能改一次」的额度"
                        } else {
                            "雁书号只能修改一次"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (!yidRegex.matches(yid)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "雁书号：$yid",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "已使用",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // 主题色（Material You 的种子色）：Android 12+ 默认跟随壁纸；
                // Web/桌面没有壁纸，就由用户自己选一个
                Spacer(Modifier.height(16.dp))
                Text(
                    if (accentSeed == null) "主题色：跟随系统（Android 12+ 跟随壁纸）" else "主题色：自定义",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 原写法：Box + clip(CircleShape) + background(hex) + border + clickable —— 
                    // 一个没有涟漪、没有语义、也不能被键盘聚焦的"假按钮"。
                    // 现在：IconButton 提供触控面积/涟漪/无障碍语义，Surface 只负责圆形色块。
                    ACCENT_PRESETS.forEach { (hex, label) ->
                        val selected = accentSeed?.removePrefix("#")?.equals(hex, ignoreCase = true) == true
                        IconButton(
                            onClick = { onAccentChange(if (selected) null else hex) },
                        ) {
                            Surface(
                                modifier = Modifier.size(24.dp),
                                shape = CircleShape,
                                color = parseSeedHex(hex),
                                border = if (selected) {
                                    androidx.compose.foundation.BorderStroke(3.dp, MaterialTheme.colorScheme.onSurface)
                                } else {
                                    androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                                },
                            ) {
                                // 选中态同时用描边表达，并给读屏一个可读的名字
                                Box(
                                    Modifier.fillMaxSize().semantics {
                                        contentDescription = if (selected) "主题色 $label（已选中）" else "主题色 $label"
                                    },
                                )
                            }
                        }
                    }
                    if (accentSeed != null) {
                        TextButton(onClick = { onAccentChange(null) }) {
                            Text("跟随系统", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                // ---------- 账号安全 ----------
                Spacer(Modifier.height(24.dp))
                HorizontalDivider()
                Spacer(Modifier.height(16.dp))
                Text(
                    "账号安全",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FilledTonalButton(
                        onClick = { showPasswordDialog = true },
                        modifier = Modifier.weight(1f),
                    ) { Text("修改密码") }
                    FilledTonalButton(
                        onClick = {
                            scope.launch {
                                try {
                                    client.logoutOtherDevices()
                                    msg = "其它设备已下线，当前设备保持登录"
                                } catch (e: Throwable) {
                                    msg = "操作失败：" + (e.message ?: e.toString())
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                    ) { Text("退出其它设备") }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { showEmailDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("修改邮箱") }

                // ---------- 两步验证 ----------
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "两步验证（TOTP）",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            if (totpEnabled) {
                                // 恢复码剩余数量对用户很重要：用完就再也救不回来了
                                "已开启 · 剩余恢复码 $totpRecoveryLeft 个"
                            } else {
                                "未开启 · 登录时除密码外还需一次 6 位验证码"
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    OutlinedButton(onClick = { showTotpDialog = true }) {
                        Text(if (totpEnabled) "关闭" else "开启")
                    }
                }

                msg?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                }
                if (showPasswordDialog) {
                    ChangePasswordDialog(
                        client = client,
                        onDismiss = { showPasswordDialog = false },
                        onDone = { msg = it },
                    )
                }
                if (showEmailDialog) {
                    ChangeEmailDialog(
                        client = client,
                        onDismiss = { showEmailDialog = false },
                        onDone = { msg = it },
                    )
                }
                // ---------- 通行密钥 ----------
                Spacer(Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            "通行密钥（Passkey）",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            when {
                                !passkeyOk -> "当前平台不支持（请用浏览器登录后绑定）"
                                passkeys.isEmpty() -> "未绑定 · 可用指纹/面容/设备 PIN 免密登录"
                                else -> "已绑定 ${passkeys.size} 个"
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    OutlinedButton(
                        enabled = passkeyOk && !busy,
                        onClick = {
                            busy = true
                            scope.launch {
                                try {
                                    val api = client.api
                                    val optionsJson = api.passkeyBegin(client.myToken)
                                    val cred = im.client.passkey.passkeyPlatform()
                                        .create(optionsJson)
                                        .getOrElse { e -> throw e }
                                    // 标签用平台名不好拿，就让用户后面自己认；这里给个默认
                                    api.passkeyFinish(client.myToken, cred, "我的设备")
                                    passkeys = api.passkeyList(client.myToken)
                                    msg = "通行密钥已绑定"
                                } catch (e: Throwable) {
                                    msg = e.message ?: e.toString()
                                } finally {
                                    busy = false
                                }
                            }
                        },
                    ) { Text("绑定") }
                }
                // 已绑定的凭据：允许逐个删除（丢了设备时用）
                passkeys.forEach { pk ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            (pk.label.ifBlank { "通行密钥" }) + " · " + pk.id.take(10) + "…",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            onClick = {
                                scope.launch {
                                    runCatching {
                                        client.api.passkeyDelete(client.myToken, pk.id)
                                        passkeys = client.api.passkeyList(client.myToken)
                                        msg = "已删除该通行密钥"
                                    }.onFailure { msg = it.message ?: it.toString() }
                                }
                            },
                        ) { Text("删除") }
                    }
                }

                if (showTotpDialog) {
                    if (totpEnabled) {
                        TotpDisableDialog(
                            client = client,
                            onDismiss = { showTotpDialog = false },
                            onDone = { m ->
                                totpEnabled = false
                                totpRecoveryLeft = 0
                                msg = m
                            },
                        )
                    } else {
                        TotpSetupDialog(
                            client = client,
                            onDismiss = { showTotpDialog = false },
                            onDone = { m, left ->
                                totpEnabled = true
                                totpRecoveryLeft = left
                                msg = m
                            },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    scope.launch {
                        val newYid = yid.trim()
                        // 只有「还没改过 + 真的改了值」才提交雁书号
                        val wantYidChange = !yidChanged && newYid.isNotEmpty() && newYid != originalYid
                        // 先本地校验再发请求：否则昵称/用户名已经改了、却在雁书号这一步报错，
                        // 界面提示"保存失败"但部分字段其实已经生效，用户完全看不懂。
                        if (wantYidChange && !yidRegex.matches(newYid)) {
                            msg = "雁书号需 5-20 位、字母开头，可含数字/_/-；请修改后再保存"
                            return@launch
                        }
                        try {
                            client.api.updateProfile(client.myToken, nickname.trim(), username.trim())
                            client.setNickname(nickname.trim())
                            if (wantYidChange) {
                                client.api.changeYid(client.myToken, newYid)
                                yidChanged = true
                                originalYid = newYid
                            }
                            msg = null
                            onDismiss()
                        } catch (e: Throwable) {
                            msg = e.message ?: e.toString()
                        }
                    }
                },
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

// ============ 三期：消息搜索 / 群信息 ============

@Composable
fun MessageSearchDialog(client: ImClient, onJump: (convId: String) -> Unit, onDismiss: () -> Unit) {
    var q by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<im.client.api.SearchHitResp>>(emptyList()) }
    var searched by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("搜索消息") },
        text = {
            Column {
                OutlinedTextField(q, { q = it }, placeholder = { Text("关键词") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                Button(
                    enabled = q.isNotBlank(),
                    onClick = {
                        scope.launch {
                            try {
                                results = client.api.searchMessages(client.myToken, q.trim())
                                searched = true
                            } catch (_: Throwable) {}
                        }
                    },
                ) { Text("搜索") }
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.height(260.dp)) {
                    items(results, key = { it.server_msg_id }) { h ->
                        // 原写法：Column + Modifier.clickable（无涟漪）。
                        // 现在：Surface(onClick) —— 涟漪、焦点与"可点击"语义由 M3 提供。
                        Surface(
                            onClick = { onJump(h.conversation_id) },
                            modifier = Modifier.fillMaxWidth(),
                            color = Color.Transparent,
                        ) {
                            Column(Modifier.padding(vertical = 8.dp)) {
                                Text(h.text, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                                Text(
                                    "${h.conversation_id.takeLast(8)} · ${momentTimeOf(h.sent_at)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    if (searched && results.isEmpty()) {
                        item {
                            // 原写法：Text(color = Color.Gray) 硬编码灰
                            Text("无结果", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

/** 按会话 ID 打开聊天（搜索跳转用） */
internal fun scopeLaunchOpenSingleByConv(client: ImClient, convId: String, onDone: (im.client.store.Conversation) -> Unit) {
    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()).launch {
        try {
            client.refreshConversations()
            val conv = client.conversations.value.firstOrNull { it.id == convId }
            if (conv != null) onDone(conv)
        } catch (_: Throwable) {}
    }
}

@Composable
fun GroupInfoDialog(client: ImClient, convId: String, title: String, onLeft: () -> Unit, onDismiss: () -> Unit) {
    var info by remember { mutableStateOf<im.client.api.GroupInfoResp?>(null) }
    var members by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var myRole by remember { mutableStateOf("member") }
    var announcement by remember { mutableStateOf("") }
    var newId by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun reload() {
        scope.launch {
            try {
                info = client.api.groupInfo(client.myToken, convId)
                announcement = info?.announcement ?: ""
                members = client.api.conversationMembers(client.myToken, convId).map { it.uid to it.nickname }
                myRole = if (info?.owner_uid == client.myUid) "owner"
                else members.firstOrNull { it.first == client.myUid }?.let { "member" } ?: "member"
            } catch (e: Throwable) {
                msg = e.message
            }
        }
    }
    LaunchedEffect(convId) { reload() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text("群公告", style = MaterialTheme.typography.titleSmall)
                Text(announcement.ifEmpty { "（未设置）" }, style = MaterialTheme.typography.bodySmall)
                if (info?.owner_uid == client.myUid || myRole == "admin") {
                    OutlinedTextField(announcement, { announcement = it }, label = { Text("编辑公告") }, minLines = 2)
                    TextButton(onClick = {
                        scope.launch { client.api.setAnnouncement(client.myToken, convId, announcement) }
                    }) { Text("保存公告") }
                }
                HorizontalDivider()
                Text("成员 (${members.size})", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(vertical = 8.dp))
                LazyColumn(Modifier.height(200.dp)) {
                    items(members, key = { it.first }) { (uid, nick) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(nick.ifEmpty { uid.takeLast(8) })
                                Text(
                                    if (info?.owner_uid == uid) "群主" else if (myRole == "admin" && uid != info?.owner_uid) "管理员" else "成员",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            // owner/admin 可踢人（不能踢群主）
                            val canKick = (info?.owner_uid == client.myUid || myRole == "admin") && uid != client.myUid && uid != info?.owner_uid
                            if (canKick) {
                                TextButton(onClick = {
                                    scope.launch { client.api.kickMember(client.myToken, convId, uid); reload() }
                                }, contentPadding = PaddingValues(0.dp)) { Text("踢出", style = MaterialTheme.typography.labelSmall) }
                            }
                        }
                    }
                }
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        newId, { newId = it },
                        placeholder = { Text("成员 UID") }, singleLine = true, modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        enabled = newId.isNotBlank(),
                        onClick = {
                            scope.launch {
                                try {
                                    client.api.addGroupMembers(client.myToken, convId, listOf(newId))
                                    newId = ""; reload()
                                } catch (e: Throwable) { msg = e.message }
                            }
                        },
                    ) { Text("邀请") }
                }
                msg?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

// ============ 账号安全：修改密码 / 修改邮箱 ============

/**
 * 修改密码。
 *
 * 服务端改完会递增令牌版本（其它设备立即失效）并下发新令牌；
 * ImClient.changePassword 内部已经换令牌 + 重连，所以这里不需要额外处理登录态。
 */
@Composable
fun ChangePasswordDialog(
    client: ImClient,
    onDismiss: () -> Unit,
    onDone: (String) -> Unit,
) {
    var oldPwd by remember { mutableStateOf("") }
    var newPwd by remember { mutableStateOf("") }
    var confirmPwd by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("修改密码") },
        text = {
            Column {
                OutlinedTextField(
                    oldPwd, { oldPwd = it },
                    label = { Text("当前密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    newPwd, { newPwd = it },
                    label = { Text("新密码（至少 6 位）") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    confirmPwd, { confirmPwd = it },
                    label = { Text("确认新密码") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "修改成功后，其它设备上的登录会立即失效",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                err?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    // 先本地校验：不一致/太短就别发请求了
                    err = when {
                        newPwd.length < 6 -> "新密码至少 6 位"
                        newPwd != confirmPwd -> "两次输入的新密码不一致"
                        newPwd == oldPwd -> "新密码不能与当前密码相同"
                        else -> null
                    }
                    if (err != null) return@TextButton
                    busy = true
                    scope.launch {
                        try {
                            client.changePassword(oldPwd, newPwd)
                            onDone("密码已修改，其它设备已下线")
                            onDismiss()
                        } catch (e: Throwable) {
                            err = e.message ?: e.toString()
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text(if (busy) "提交中…" else "确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
        },
    )
}

/**
 * 修改 / 绑定邮箱。
 *
 * 站点开启邮箱验证码时必填 code（与注册同一套校验）；未开启时直接生效。
 * 这里不去预判站点开关 —— 让服务端说，UI 只把可选性写清楚。
 */
@Composable
fun ChangeEmailDialog(
    client: ImClient,
    onDismiss: () -> Unit,
    onDone: (String) -> Unit,
) {
    var email by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    var tip by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    // 与注册页一致的 60 秒冷却，避免连点发送
    var cooldown by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(cooldown) {
        if (cooldown > 0) {
            delay(1000)
            cooldown -= 1
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("修改邮箱") },
        text = {
            Column {
                OutlinedTextField(
                    email, { email = it },
                    label = { Text("邮箱") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        code, { code = it },
                        label = { Text("验证码") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        enabled = email.isNotBlank() && !busy && cooldown == 0,
                        onClick = {
                            busy = true
                            scope.launch {
                                try {
                                    client.api.sendEmailCode(email.trim())
                                    tip = "验证码已发送，请查收邮箱"
                                    err = null
                                    cooldown = 60
                                } catch (e: Throwable) {
                                    err = e.message ?: e.toString()
                                } finally {
                                    busy = false
                                }
                            }
                        },
                    ) { Text(if (cooldown > 0) cooldown.toString() + " 秒" else "发送") }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "站点开启邮箱验证码时必填；留空邮箱可解除绑定",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                tip?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                }
                err?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            client.updateEmail(email.trim(), code.trim())
                            onDone(if (email.isBlank()) "邮箱已解绑" else "邮箱已更新")
                            onDismiss()
                        } catch (e: Throwable) {
                            err = e.message ?: e.toString()
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text(if (busy) "提交中…" else "保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") } },
    )
}



// ============ 两步验证（TOTP）============

/**
 * 开启两步验证：取密钥 → 用户把密钥录进验证器 → 提交一次验证码确认 → 展示恢复码。
 *
 * 中间那一步「用验证码确认」不能省：扫完码就关页面的话，用户会把自己锁在门外。
 * 恢复码只展示这一次，库里存的是哈希 —— 所以要说清楚「现在就存好」。
 */
@Composable
fun TotpSetupDialog(
    client: ImClient,
    onDismiss: () -> Unit,
    onDone: (message: String, recoveryLeft: Int) -> Unit,
) {
    var secret by remember { mutableStateOf("") }
    var otpauth by remember { mutableStateOf("") }
    var qrRows by remember { mutableStateOf<List<String>>(emptyList()) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var copied by remember { mutableStateOf("") }
    // 存档文本里要带上「谁、什么时候」—— 半年后从抽屉里翻出来也能对上
    val accountLabel = remember { client.myUid }
    val generatedAt = remember { nowTimestamp() }
    var code by remember { mutableStateOf("") }
    var recovery by remember { mutableStateOf<List<String>>(emptyList()) }
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        busy = true
        try {
            val s = client.api.totpSetup(client.myToken)
            secret = s.secret
            otpauth = s.otpauth_url
            qrRows = s.qr_rows
        } catch (e: Throwable) {
            err = e.message ?: e.toString()
        } finally {
            busy = false
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(if (recovery.isEmpty()) "开启两步验证" else "请保存恢复码") },
        text = {
            Column {
                if (recovery.isEmpty()) {
                    // 二维码矩阵来自服务端：三端共用一份实现，也不用在线二维码 API
                    //（那等于把 TOTP 密钥发给第三方）
                    if (qrRows.isNotEmpty()) {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Surface(
                                color = androidx.compose.ui.graphics.Color.White,
                                shape = MaterialTheme.shapes.small,
                            ) {
                                QrMatrix(
                                    rows = qrRows,
                                    modifier = Modifier.padding(8.dp).size(176.dp),
                                )
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "1. 用验证器 App 扫上面的二维码（扫不了就手动输入密钥）",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    } else {
                        Text(
                            "1. 在验证器 App 里手动输入下面的密钥",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("密钥", style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(secret))
                            copied = "密钥"
                        }, enabled = secret.isNotBlank()) {
                            Text(if (copied == "密钥") "已复制" else "复制")
                        }
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            if (secret.isBlank()) "生成中…" else secret.chunked(4).joinToString(" "),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "2. 填入验证器显示的 6 位验证码以确认绑定",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        code, { code = it.filter { c -> c.isDigit() }.take(6) },
                        label = { Text("6 位验证码") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "otpauth 地址（可直接导入验证器 App）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(otpauth))
                            copied = "地址"
                        }, enabled = otpauth.isNotBlank()) {
                            Text(if (copied == "地址") "已复制" else "复制")
                        }
                    }
                    Text(
                        otpauth,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                } else {
                    Text(
                        "这些恢复码只显示这一次。手机丢失时用它们登录，每个只能用一次，请现在保存好。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = MaterialTheme.shapes.small,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(Modifier.padding(12.dp)) {
                            recovery.chunked(2).forEach { row ->
                                Text(
                                    row.joinToString("    "),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    // 复制 / 保存 / 打印：三种都是「把这张纸留下来」，
                    // 能力缺失的平台直接不显示按钮（而不是点了没反应）
                    val exporter = remember { runCatching { recoveryExport() }.getOrNull() }
                    val fullText = recoveryCodesAsText(
                        account = accountLabel,
                        codes = recovery,
                        generatedAt = generatedAt,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = {
                            clipboard.setText(AnnotatedString(fullText))
                            copied = "恢复码"
                        }) { Text(if (copied == "恢复码") "已复制" else "复制") }
                        if (exporter?.canSave == true) {
                            TextButton(enabled = !busy, onClick = {
                                busy = true
                                scope.launch {
                                    val ok = exporter.save("雁书恢复码.txt", fullText)
                                    copied = if (ok) "已保存" else ""
                                    if (!ok) err = "保存失败或被取消"
                                    busy = false
                                }
                            }) { Text(if (copied == "已保存") "已保存" else "下载") }
                        }
                        if (exporter?.canPrint == true) {
                            TextButton(enabled = !busy, onClick = {
                                busy = true
                                scope.launch {
                                    val ok = exporter.print(fullText)
                                    if (!ok) err = "无法调起打印"
                                    busy = false
                                }
                            }) { Text("打印") }
                        }
                    }
                }
                err?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy,
                onClick = {
                    if (recovery.isNotEmpty()) {
                        onDone("两步验证已开启", recovery.size)
                        onDismiss()
                        return@TextButton
                    }
                    if (code.length != 6) {
                        err = "请输入 6 位验证码"
                        return@TextButton
                    }
                    busy = true
                    scope.launch {
                        try {
                            recovery = client.api.totpEnable(client.myToken, code)
                            err = null
                        } catch (e: Throwable) {
                            err = e.message ?: e.toString()
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text(if (recovery.isEmpty()) (if (busy) "提交中…" else "确认开启") else "我已保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !busy) { Text(if (recovery.isEmpty()) "取消" else "关闭") }
        },
    )
}

/** 关闭两步验证：需要当前验证码或一个恢复码 */
@Composable
fun TotpDisableDialog(
    client: ImClient,
    onDismiss: () -> Unit,
    onDone: (message: String) -> Unit,
) {
    var code by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("关闭两步验证") },
        text = {
            Column {
                Text(
                    "关闭后登录只需要密码。请输入当前验证码，或用任意一个恢复码确认。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    code, { code = it.trim() },
                    label = { Text("验证码或恢复码") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                err?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !busy && code.isNotBlank(),
                onClick = {
                    busy = true
                    scope.launch {
                        try {
                            client.api.totpDisable(client.myToken, code.trim())
                            onDone("两步验证已关闭")
                            onDismiss()
                        } catch (e: Throwable) {
                            err = e.message ?: e.toString()
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text(if (busy) "提交中…" else "关闭") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") } },
    )
}


/**
 * 把服务端给的二维码矩阵画出来。
 *
 * 只画点、不做纠错与编码 —— 编码在服务端完成（三端共用），这里纯渲染。
 * 同色连续段合并成一次 drawRect：57x57 的码逐格画会有 3000+ 次绘制调用，
 * 每 30 秒刷新一次设置页时能明显感到卡顿。
 */
@Composable
fun QrMatrix(rows: List<String>, modifier: Modifier = Modifier) {
    val dark = MaterialTheme.colorScheme.onSurface
    androidx.compose.foundation.Canvas(modifier) {
        val n = rows.size
        if (n == 0) return@Canvas
        val cell = size.minDimension / n
        for (r in 0 until n) {
            val line = rows[r]
            var c = 0
            while (c < line.length) {
                if (line[c] != '1') {
                    c++
                    continue
                }
                var end = c
                while (end < line.length && line[end] == '1') end++
                drawRect(
                    color = dark,
                    topLeft = androidx.compose.ui.geometry.Offset(c * cell, r * cell),
                    size = androidx.compose.ui.geometry.Size((end - c) * cell, cell),
                )
                c = end
            }
        }
    }
}


/** 生成 "2026-11-08 00:00" 形式的时间戳（common 层没有 java.time，这里用 epoch 手动换算） */
private fun nowTimestamp(): String {
    val ms = nowEpochMillis()
    val totalSec = ms / 1000
    val days = totalSec / 86400
    val secOfDay = (totalSec % 86400).toInt()
    val hh = (secOfDay / 3600 + 8) % 24       // 东八区
    val mm = (secOfDay % 3600) / 60
    // 1970-01-01 起的日期换算（够用即可，不引入 java.time）
    var y = 1970
    var d = days
    while (true) {
        val leap = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0
        val len = if (leap) 366 else 365
        if (d < len) break
        d -= len
        y++
    }
    val leap = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0
    val monthLen = intArrayOf(31, if (leap) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
    var m = 0
    while (m < 12 && d >= monthLen[m]) {
        d -= monthLen[m]
        m++
    }
    return y.toString() + "-" + (m + 1).toString().padStart(2, '0') + "-" +
        (d + 1).toString().padStart(2, '0') + " " +
        hh.toString().padStart(2, '0') + ":" + mm.toString().padStart(2, '0')
}

