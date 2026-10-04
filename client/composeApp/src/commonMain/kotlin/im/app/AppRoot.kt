package im.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.zIndex
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import im.client.ImClient
import im.client.auth.SavedSession
import im.client.auth.clearSession
import im.client.auth.loadSession
import im.client.auth.oidcTokenFromLaunch
import im.client.auth.saveSession
import im.client.net.ConnState
import im.client.proto.Msg
import im.client.proto.MsgType
import im.client.store.Conversation
import im.client.file.pickFile
import im.client.openUrl
import im.client.registerTurnstileCallback
import im.client.renderTurnstileWidget
import im.client.wireCallbacks
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

internal var DEFAULT_API = "http://127.0.0.1:10002"
internal var DEFAULT_WS = "ws://127.0.0.1:10001/ws"

@Composable
fun AppRoot() {
    YanshuTheme {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            var client by remember { mutableStateOf<ImClient?>(null) }
            // 恢复会话期间先出启动画面，避免闪一下登录页
            var restoring by remember { mutableStateOf(true) }

            // 用本地存的 token 直接恢复登录 —— 桌面/Android 重启、Web 刷新都不该掉线
            LaunchedEffect(Unit) {
                // OIDC 回调把令牌带回来了（Web 读 location.hash、Android 读启动 Intent）：
                // 优先自动登录，用户不需要任何手工操作
                val oidcToken = oidcTokenFromLaunch()
                if (oidcToken != null) {
                    try {
                        val c = ImClient(DEFAULT_API.trimEnd('/'), DEFAULT_WS)
                        c.wireCallbacks()
                        c.loginWithToken(oidcToken)
                        c.startSession()
                        saveSession(SavedSession(c.myToken, c.apiBaseUrl, c.gatewayWsUrl))
                        client = c
                        restoring = false
                        return@LaunchedEffect
                    } catch (e: Throwable) {
                        // 令牌无效/被撤销：继续走下面的常规恢复流程（必要时落到登录页）
                    }
                }
                val saved = loadSession()
                if (saved != null && saved.token.isNotEmpty() && saved.apiBase.isNotEmpty()) {
                    try {
                        val c = ImClient(saved.apiBase, saved.wsBase)
                        c.wireCallbacks()
                        c.loginWithToken(saved.token)
                        c.startSession()
                        client = c
                    } catch (e: Throwable) {
                        val m = e.message ?: ""
                        // 只有明确的鉴权失败才丢弃会话；网络抖动时保留，下次启动还能恢复
                        if (m.contains("401") || m.contains("403") || m.contains("404")) clearSession()
                    }
                }
                restoring = false
            }

            when {
                restoring -> SplashScreen()
                client == null -> LoginScreen(onLoggedIn = { client = it })
                else -> {
                    val c = client!!
                    // 服务端拒绝鉴权（token 失效 / 账号被封 / 令牌被撤销）：清会话回登录页。
                    // 没有这条通路时，用户只会看到一直"连接中"，唯一的出路是手动清数据重装。
                    val invalid by c.sessionInvalid.collectAsState()
                    LaunchedEffect(invalid) {
                        if (invalid != null) {
                            clearSession()
                            c.stop()
                            client = null
                        }
                    }
                    MainScreen(
                        c,
                        onLogout = {
                            clearSession()
                            c.stop()
                            client = null
                        },
                    )
                }
            }
        }
    }
}

/** 启动画面：会话恢复完成前占位 */
@Composable
private fun SplashScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier.size(56.dp).background(
                    MaterialTheme.colorScheme.primary,
                    androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                ),
                contentAlignment = Alignment.Center,
            ) { Text("雁", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.headlineSmall) }
            Spacer(Modifier.height(16.dp))
            Text("正在恢复登录状态…", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
        }
    }
}

// ---------- 登录 ----------

@kotlinx.serialization.Serializable
data class SiteConfigResp(
    val registration_enabled: Boolean = true,
    val turnstile_enabled: Boolean = false,
    val turnstile_site_key: String = "",
    val email_code_enabled: Boolean = false,
    val oidc_providers: List<OidcProviderInfo> = emptyList(),
)

@kotlinx.serialization.Serializable
data class OidcProviderInfo(val name: String, val authorize_url: String)


/** 拉 site-config（不走 token） */
suspend fun apiSiteConfig(apiBase: String): SiteConfigResp {
    val text = im.client.api.rawHttpText("GET", "$apiBase/v1/site-config")
    return kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        .decodeFromString(SiteConfigResp.serializer(), text)
}

suspend fun apiSendEmailCode(apiBase: String, email: String, turnstileToken: String): Boolean {
    val body = """{"email":"$email","turnstile_token":"$turnstileToken"}"""
    val text = im.client.api.rawHttpText("POST", "$apiBase/v1/email/send-code", body)
    return text.contains("ok")
}

// ---------- 主界面 ----------

@Composable
fun MainScreen(client: ImClient, onLogout: () -> Unit) {
    var selected by remember { mutableStateOf<Conversation?>(null) }
    val state by client.connectionState.collectAsState()
    val callState by client.callController.uiState.collectAsState()
    var showNewGroupDialog by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf("chat") }
    var showProfile by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    // 通讯录事件提示条
    LaunchedEffect(Unit) {
        client.connection.contactEvents.collect { ev ->
            val text = when (ev.type) {
                "request" -> "${ev.nickname.ifEmpty { ev.fromUid.takeLast(6) }} 请求添加你为好友"
                "accepted" -> "${ev.nickname.ifEmpty { ev.fromUid.takeLast(6) }} 已通过你的好友申请"
                else -> "通讯录事件"
            }
            snackbar.showSnackbar(text)
        }
    }

    Box(Modifier.fillMaxSize()) {
    Row(Modifier.fillMaxSize()) {
        // 左侧栏
        Surface(
            modifier = Modifier.width(300.dp).fillMaxHeight(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.fillMaxSize()) {
                // 当前用户：头像 + 昵称 + 连接状态，点击进资料；右侧退出
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(
                        client.myNickname.ifEmpty { client.myUid.takeLast(6) },
                        size = 42.dp,
                        round = true,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f).clickable { showProfile = true }) {
                        Text(
                            client.myNickname.ifEmpty { client.myUid.takeLast(6) },
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(3.dp))
                        StatusPill(state)
                    }
                    TextButton(onClick = onLogout) { Text("退出") }
                }
                // 视图切换：聊天 / 通讯录 / 朋友圈
                SegmentedRow(
                    listOf("chat" to "聊天", "contacts" to "通讯录", "moments" to "朋友圈"),
                    selected = tab,
                ) { id ->
                    tab = id
                    if (id != "chat") selected = null
                }
                HorizontalDivider()
                when (tab) {
                    "contacts" -> ContactsView(client, onOpenChat = { peerUid ->
                        scopeLaunchOpenSingle(client, peerUid) { conv -> selected = conv; tab = "chat" }
                    })
                    "moments" -> MomentsView(client)
                    else -> {
                        var showSearch by remember { mutableStateOf(false) }
                        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                            OutlinedButton(
                                onClick = { showSearch = true },
                                modifier = Modifier.fillMaxWidth(),
                                contentPadding = PaddingValues(vertical = 8.dp),
                                shape = RoundedCornerShape(10.dp),
                            ) { Text("搜索消息", style = MaterialTheme.typography.labelMedium) }
                        }
                        ConversationList(client, selected) { selected = it }
                        if (showSearch) {
                            MessageSearchDialog(client, onJump = { convId ->
                                scopeLaunchOpenSingleByConv(client, convId) { conv -> selected = conv }
                            }) { showSearch = false }
                        }
                    }
                }
            }
        }
        VerticalDivider()
        if (selected == null) {
            Box(
                Modifier.weight(1f).fillMaxHeight().background(MaterialTheme.colorScheme.background),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.size(76.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("雁", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                    Spacer(Modifier.height(18.dp))
                    Text(
                        when (tab) {
                            "contacts" -> "从通讯录选一个好友开始聊天"
                            "moments" -> "朋友圈里还没有内容"
                            else -> "从左侧选择一个会话开始聊天"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            ChatScreen(client, selected!!, Modifier.weight(1f))
        }
    }
    // Snackbar 容器
    SnackbarHost(
        hostState = snackbar,
        modifier = Modifier.align(Alignment.BottomCenter).zIndex(1f),
    )
    // 通话遮罩层
    when (callState.state) {
        im.client.CallState.RingingIn -> IncomingCallOverlay(
            peer = callState.peerUid.takeLast(8),
            onAccept = { client.callController.acceptCall() },
            onReject = { client.callController.rejectCall() },
        )
        im.client.CallState.RingingOut -> CallingOverlay(
            peer = callState.peerUid.takeLast(8),
            onCancel = { client.callController.cancelCall() },
        )
        im.client.CallState.InCall -> InCallOverlay(
            onHangUp = { client.callController.hangUp() },
        )
        else -> {}
    }

    if (showProfile) {
        ProfileDialog(client) { showProfile = false }
    }

    if (showNewGroupDialog) {
        NewGroupDialog(
            client = client,
            onCreated = { conv -> selected = conv },
            onDismiss = { showNewGroupDialog = false },
        )
    }
    } // 外层 Box
}

@Composable
fun IncomingCallOverlay(peer: String, onAccept: () -> Unit, onReject: () -> Unit) {
    AlertDialog(
        onDismissRequest = onReject,
        title = { Text("来电 · $peer") },
        text = { Text("对方邀请你进行音视频通话") },
        confirmButton = { Button(onClick = onAccept) { Text("接听") } },
        dismissButton = { OutlinedButton(onClick = onReject) { Text("拒绝") } },
    )
}

@Composable
fun CallingOverlay(peer: String, onCancel: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xCC1B1B1F)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("正在呼叫 $peer …", color = Color.White, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(20.dp))
            Button(onClick = onCancel) { Text("取消") }
        }
    }
}

@Composable
fun InCallOverlay(onHangUp: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0xCC1B1B1B)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("通话中（媒体由平台 WebRTC 承载）", color = Color.White, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            Text("音视频画面在通话面板中显示", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(24.dp))
            Button(onClick = onHangUp) { Text("挂断") }
        }
    }
}

@Composable
fun NewGroupDialog(client: ImClient, onCreated: (Conversation) -> Unit, onDismiss: () -> Unit) {
    var groupName by remember { mutableStateOf("") }
    var peerInput by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新建群聊") },
        text = {
            Column {
                OutlinedTextField(groupName, { groupName = it }, label = { Text("群名称") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(peerInput, { peerInput = it }, label = { Text("成员 UID") }, singleLine = true)
                error?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val name = groupName.trim()
                    val peer = peerInput.trim()
                    if (name.isEmpty() || peer.isEmpty()) {
                        error = "请填写群名称与成员 UID"
                        return@TextButton
                    }
                    scope.launch {
                        try {
                            val gid = client.api.createGroup(client.myToken, name, listOf(peer))
                            client.refreshConversations()
                            client.conversations.value.firstOrNull { it.id == gid }?.let(onCreated)
                            onDismiss()
                        } catch (e: Throwable) {
                            error = e.message ?: e.toString()
                        }
                    }
                },
            ) { Text("创建") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

private fun scopeLaunch(block: suspend () -> Unit) {
    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob()).launch { block() }
}

@Composable
fun ConversationList(client: ImClient, selected: Conversation?, onSelect: (Conversation) -> Unit) {
    val list by client.conversations.collectAsState()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 6.dp)) {
        if (list.isEmpty()) {
            item { EmptyHint("还没有会话，去通讯录找个人聊聊吧") }
        }
        items(list, key = { it.id }) { conv ->
            val isSel = selected?.id == conv.id
            val title = conv.title.ifEmpty { conv.id.takeLast(8) }
            val unread = (conv.lastSeq - conv.readSeq).coerceAtLeast(0)
            Surface(
                color = if (isSel) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                else Color.Transparent,
                onClick = { onSelect(conv) },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Row(Modifier.padding(horizontal = 10.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                    Avatar(title, size = 42.dp, round = true)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            title,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(3.dp))
                        Text(
                            if (conv.type == "group") "群聊" else "单聊",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (unread > 0) {
                        Box(
                            Modifier
                                .background(MaterialTheme.colorScheme.error, RoundedCornerShape(50))
                                .padding(horizontal = 6.dp, vertical = 1.dp),
                        ) {
                            Text(
                                unread.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onError,
                            )
                        }
                        Spacer(Modifier.width(6.dp))
                    }
                    if (conv.type == "group") {
                        var infoOpen by remember { mutableStateOf(false) }
                        TextButton(onClick = { infoOpen = true }, contentPadding = PaddingValues(0.dp)) { Text("⋯") }
                        if (infoOpen) {
                            GroupInfoDialog(client, conv.id, conv.title, onLeft = { onSelect(Conversation(conv.id, conv.type, conv.title, conv.lastSeq, conv.readSeq)) }) { infoOpen = false }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ChatScreen(client: ImClient, conversation: Conversation, modifier: Modifier = Modifier) {
    val msgs by remember(conversation.id) {
        client.messages.map { map -> map[conversation.id] ?: emptyList() }
    }.collectAsState(initial = emptyList())
    var input by remember { mutableStateOf("") }
    var sendError by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(conversation.id, msgs.size) {
        if (msgs.isNotEmpty()) listState.animateScrollToItem(msgs.size - 1)
    }

    val title = conversation.title.ifEmpty { conversation.id.takeLast(8) }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // 头部
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Avatar(title, size = 38.dp, round = true)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        if (conversation.type == "group") "群聊" else "单聊",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (conversation.type == "single") {
                    IconButton(
                        onClick = {
                            // 从会话 ID 解析对端 UID：s_{uidA}_{uidB}
                            val uids = conversation.id.removePrefix("s_").split("_")
                            val peer = uids.firstOrNull { it != client.myUid }
                            if (peer != null) client.callController.startCall(peer)
                        },
                    ) { Text("📞", style = MaterialTheme.typography.titleMedium) }
                }
            }
        }
        HorizontalDivider()
        // 消息列表
        LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(12.dp)) {
            items(msgs, key = { it.serverMsgId.ifEmpty { it.clientMsgId ?: "" } }) { m ->
                MessageBubble(client, m, mine = m.fromUid == client.myUid)
                Spacer(Modifier.height(8.dp))
            }
        }
        HorizontalDivider()
        // 输入栏
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = {
                        scope.launch {
                            val file = pickFile() ?: return@launch
                            try {
                                client.sendAttachment(conversation.id, file)
                            } catch (e: Throwable) {
                                sendError = e.message ?: e.toString()
                            }
                        }
                    },
                ) { Text("📎", style = MaterialTheme.typography.titleMedium) }
                Spacer(Modifier.width(4.dp))
                OutlinedTextField(
                    input, { input = it },
                    placeholder = { Text("输入消息…") },
                    modifier = Modifier.weight(1f),
                    maxLines = 4,
                    shape = RoundedCornerShape(14.dp),
                )
                Spacer(Modifier.width(10.dp))
                Button(
                    enabled = input.isNotBlank(),
                    onClick = {
                        val text = input
                        input = ""
                        scope.launch { client.sendMessage(conversation.id, text) }
                    },
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 10.dp),
                ) { Text("发送") }
            }
        }
        sendError?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 12.dp).padding(bottom = 6.dp),
            )
        }
    }
}

@Composable
fun MessageBubble(client: ImClient, m: Msg, mine: Boolean) {
    // 自己的消息尾巴在右下角，对方的在左下角 —— 靠这一处圆角差来区分方向
    val shape = if (mine) RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)
    else RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)
    val base = Modifier
        .widthIn(max = 340.dp)
        .clip(shape)
        .background(if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface)
        .let { if (mine) it else it.border(1.dp, MaterialTheme.colorScheme.outline, shape) }

    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        // 发送失败的消息整条可点：点一下用同一个 client_msg_id 重发（服务端幂等，不会重复）
        val clickableBase = if (m.failed) {
            base.clickable { m.clientMsgId?.let { id -> client.resend(id) } }
        } else {
            base
        }
        Box(clickableBase.padding(horizontal = 13.dp, vertical = 9.dp)) {
            SelectionContainer {
                Column {
                    when (m.msgType) {
                        MsgType.Image, MsgType.File, MsgType.Audio, MsgType.Video -> AttachmentView(client, m)
                        else -> Text(
                            m.text,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        when {
                            m.sending -> "发送中…"
                            m.failed -> "发送失败，点击重试"
                            else -> timeOf(m.sentAt)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (mine) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.7f)
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                    )
                }
            }
        }
    }
}

@Composable
fun AttachmentView(client: ImClient, m: Msg) {
    val att = m.attachment
    val scope = rememberCoroutineScope()
    val label = when (m.msgType) {
        MsgType.Image -> "🖼 ${att?.name ?: m.text}"
        MsgType.File -> "📄 ${att?.name ?: m.text}"
        MsgType.Audio -> "🎤 ${att?.name ?: m.text}"
        MsgType.Video -> "🎬 ${att?.name ?: m.text}"
        else -> m.text
    }
    Column {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        att?.size?.let {
            Text(
                formatSize(it),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
        // att.url 现在是对象 key，不是可直接打开的地址：打开前用登录态换一张短时票据，
        // 这样消息体里不会出现长期有效的登录 JWT。
        val attKey = att?.url?.takeIf { it.isNotEmpty() }
        if (attKey != null) {
            TextButton(onClick = {
                scope.launch {
                    try {
                        openUrl(client.api.attachmentUrl(client.myToken, attKey))
                    } catch (_: Throwable) {
                    }
                }
            }) { Text("下载", style = MaterialTheme.typography.labelMedium) }
        }
    }
}

private fun formatSize(bytes: Long): String {
    fun fmt(v: Double, unit: String): String {
        // wasm/JS 目标没有 String.format，手动保留一位小数
        val int = v.toInt()
        val frac = ((v - int) * 10).toInt()
        return if (frac == 0) "$int $unit" else "$int.$frac $unit"
    }
    return when {
        bytes >= 1024 * 1024 -> fmt(bytes / 1024.0 / 1024.0, "MB")
        bytes >= 1024 -> fmt(bytes / 1024.0, "KB")
        else -> "$bytes B"
    }
}


private fun timeOf(ms: Long): String = im.client.formatChatTime(ms)
