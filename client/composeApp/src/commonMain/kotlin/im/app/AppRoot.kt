package im.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.zIndex
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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

// 默认地址来自构建期配置（-Pim.api / -Pim.ws，见 composeApp/build.gradle.kts 的 generateImConfig）。
// Web 端若构建时没有显式钉住地址，applyWebDefaults() 会按页面域名覆盖它。
internal var DEFAULT_API = ImBuildConfig.API_BASE
internal var DEFAULT_WS = ImBuildConfig.WS_BASE

@Composable
fun AppRoot() {
    // 用户选的主题色（null = 跟随平台默认：Android 12+ 跟随壁纸，其它平台用品牌色）
    var accentSeed by remember { mutableStateOf(loadAccentSeed()) }
    YanshuTheme(seedHex = accentSeed) {
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
                        // 只有明确的鉴权失败才丢弃会话；网络抖动时保留，下次启动还能恢复。
                        // 用结构化的 status 判断，不再在文案里找数字 —— 文案已经改成
                        // 「登录已过期，请重新登录」这类人话，找 "401" 会失效。
                        val st = (e as? im.client.api.ApiException)?.status ?: 0
                        if (st == 401 || st == 403 || st == 404) clearSession()
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
                        accentSeed = accentSeed,
                        onAccentChange = { hex ->
                            accentSeed = hex
                            saveAccentSeed(hex)
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
            // 原写法：Box + background(primary, RoundedCornerShape(16.dp)) 自绘色块。
            // 现在：Surface 提供形状与底色；文字颜色用 onPrimary 语义角色。
            Surface(
                modifier = Modifier.size(56.dp),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("雁", style = MaterialTheme.typography.headlineSmall)
                }
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "正在恢复登录状态…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
fun MainScreen(
    client: ImClient,
    onLogout: () -> Unit,
    accentSeed: String? = null,
    onAccentChange: (String?) -> Unit = {},
) {
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
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Avatar(
                        client.myNickname.ifEmpty { client.myUid.takeLast(6) },
                        size = 42.dp,
                        round = true,
                    )
                    Spacer(Modifier.width(8.dp))
                    // 原写法：Column + Modifier.clickable（无涟漪、无按钮语义）。
                    // 现在：M3 Surface 的 onClick 重载 —— 自带涟漪、可聚焦、可无障碍点击。
                    Surface(
                        onClick = { showProfile = true },
                        modifier = Modifier.weight(1f),
                        shape = MaterialTheme.shapes.medium,
                        color = Color.Transparent,
                    ) {
                        Column(Modifier.padding(vertical = 4.dp)) {
                            Text(
                                client.myNickname.ifEmpty { client.myUid.takeLast(6) },
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(4.dp))
                            StatusPill(state)
                        }
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
                        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp)) {
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
            // 原写法：Box + background(background) / Box + clip + background(primaryContainer)。
            // 现在：Surface 承载底色与形状。
            Surface(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                color = MaterialTheme.colorScheme.background,
            ) {
                Box(contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Surface(
                        modifier = Modifier.size(72.dp),
                        shape = MaterialTheme.shapes.extraLarge,
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("雁", style = MaterialTheme.typography.headlineMedium)
                        }
                    }
                    Spacer(Modifier.height(16.dp))
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
        ProfileDialog(
            client = client,
            onDismiss = { showProfile = false },
            accentSeed = accentSeed,
            onAccentChange = onAccentChange,
        )
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

// 原写法：Box + background(scrim) 自绘遮罩，文字用硬编码 Color.White。
// 现在：Surface 承载遮罩色（scrim），文字用 onInverseSurface —— 深浅色下都可读。
@Composable
fun CallingOverlay(peer: String, onCancel: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f),
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("正在呼叫 $peer …", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(24.dp))
                Button(onClick = onCancel) { Text("取消") }
            }
        }
    }
}

@Composable
fun InCallOverlay(onHangUp: () -> Unit) {
    // 同上：Surface 承担遮罩，文字用语义色而不是硬编码白色
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f),
        contentColor = MaterialTheme.colorScheme.inverseOnSurface,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("通话中（媒体由平台 WebRTC 承载）", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(
                    "音视频画面在通话面板中显示",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.inverseOnSurface.copy(alpha = 0.8f),
                )
                Spacer(Modifier.height(24.dp))
                Button(onClick = onHangUp) { Text("挂断") }
            }
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

@OptIn(ExperimentalMaterial3Api::class)
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
                color = if (isSel) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                onClick = { onSelect(conv) },
                shape = MaterialTheme.shapes.large,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                // M3 ListItem：leading / headline / supporting / trailing 四段式行版式
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    leadingContent = { Avatar(title, size = 42.dp, round = true) },
                    headlineContent = {
                        Text(
                            title,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    supportingContent = {
                        Text(
                            if (conv.type == "group") "群聊" else "单聊",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (unread > 0) {
                                // 原写法：Box + background(error) + 手动 padding 拼一个药丸。
                                // 现在：M3 Badge —— 未读数用它的标准形态，颜色/圆角/字号由主题给。
                                Badge { Text(unread.toString()) }
                            }
                            if (conv.type == "group") {
                                var infoOpen by remember { mutableStateOf(false) }
                                IconButton(onClick = { infoOpen = true }) {
                                    // 可访问性：图标按钮必须有语义描述
                                    Text(
                                        "⋯",
                                        style = MaterialTheme.typography.titleMedium,
                                        modifier = Modifier.semantics { contentDescription = "群信息" },
                                    )
                                }
                                if (infoOpen) {
                                    GroupInfoDialog(
                                        client, conv.id, conv.title,
                                        onLeft = {
                                            onSelect(
                                                Conversation(conv.id, conv.type, conv.title, conv.lastSeq, conv.readSeq)
                                            )
                                        },
                                    ) { infoOpen = false }
                                }
                            }
                        }
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
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
    // 原写法：Column + Modifier.background(colorScheme.background) 自绘页面底色。
    // 现在：M3 Scaffold 作为页面容器 + TopAppBar 直接挂在 topBar 上。
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
        TopAppBar(
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Avatar(title, size = 36.dp, round = true)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(
                            title,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            if (conversation.type == "group") "群聊" else "单聊",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            actions = {
                if (conversation.type == "single") {
                    IconButton(
                        onClick = {
                            // 从会话 ID 解析对端 UID：s_{uidA}_{uidB}
                            val uids = conversation.id.removePrefix("s_").split("_")
                            val peer = uids.firstOrNull { it != client.myUid }
                            if (peer != null) client.callController.startCall(peer)
                        },
                    ) {
                        Text(
                            "📞",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.semantics { contentDescription = "发起通话" },
                        )
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer,
            ),
        )
        },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
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
                // 间距统一为 8 的倍数（原来是 14/10）
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
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
                ) {
                    Text(
                        "📎",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { contentDescription = "发送附件" },
                    )
                }
                Spacer(Modifier.width(4.dp))
                // M3 filled TextField + pill 形状（Material You 的聊天输入框就是这样）
                TextField(
                    input, { input = it },
                    placeholder = { Text("输入消息…") },
                    modifier = Modifier.weight(1f),
                    maxLines = 4,
                    shape = MaterialTheme.shapes.extraLarge,
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                    ),
                )
                Spacer(Modifier.width(8.dp))
                // M3 的 FilledIconButton：圆形填充按钮
                FilledIconButton(
                    onClick = {
                        val text = input
                        input = ""
                        scope.launch { client.sendMessage(conversation.id, text) }
                    },
                    enabled = input.isNotBlank(),
                    modifier = Modifier.size(44.dp),
                ) { Text("➤", style = MaterialTheme.typography.titleMedium) }
            }
        }
        sendError?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
            )
        }
        }
    }
}

@Composable
fun MessageBubble(client: ImClient, m: Msg, mine: Boolean) {
    // 自己的消息尾巴在右下角，对方的在左下角 —— 靠这一处圆角差来区分方向
    val shape = if (mine) RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp)
    else RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp)
    // 原写法：Box + clip(自定义 RoundedCornerShape) + background(...) + 手动 border 画气泡。
    // 现在：Surface 承担形状、底色与描边；发送失败时用 Surface 的 onClick 重载拿到涟漪与语义。
    val bubbleColor = if (mine) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
    val bubbleContent = if (mine) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface

    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        // 发送失败的消息整条可点：点一下用同一个 client_msg_id 重发（服务端幂等，不会重复）
        val onBubbleClick = if (m.failed) {
            { m.clientMsgId?.let { id -> client.resend(id) }; Unit }
        } else null

        val content: @Composable () -> Unit = {
            SelectionContainer {
                Column {
                    when (m.msgType) {
                        MsgType.Image, MsgType.File, MsgType.Audio, MsgType.Video -> AttachmentView(client, m)
                        else -> Text(m.text, style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        when {
                            m.sending -> "发送中…"
                            m.failed -> "发送失败，点击重试"
                            else -> timeOf(m.sentAt)
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        val bubbleModifier = Modifier.widthIn(max = 320.dp)
        if (onBubbleClick != null) {
            Surface(
                onClick = onBubbleClick,
                modifier = bubbleModifier,
                shape = shape,
                color = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ) {
                Box(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) { content() }
            }
        } else {
            Surface(
                modifier = bubbleModifier,
                shape = shape,
                color = bubbleColor,
                contentColor = bubbleContent,
                border = if (mine) null else androidx.compose.foundation.BorderStroke(
                    1.dp, MaterialTheme.colorScheme.outlineVariant,
                ),
            ) {
                Box(Modifier.padding(horizontal = 8.dp, vertical = 8.dp)) { content() }
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
