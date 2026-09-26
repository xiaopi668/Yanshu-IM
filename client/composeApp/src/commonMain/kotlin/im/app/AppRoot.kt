package im.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import im.client.ImClient
import im.client.net.ConnState
import im.client.proto.Msg
import im.client.proto.MsgType
import im.client.store.Conversation
import im.client.file.pickFile
import im.client.openUrl
import im.client.wireCallbacks
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private const val DEFAULT_API = "http://127.0.0.1:10002"
private const val DEFAULT_WS = "ws://127.0.0.1:10001/ws"

@Composable
fun AppRoot() {
    MaterialTheme {
        Surface(Modifier.fillMaxSize()) {
            var client by remember { mutableStateOf<ImClient?>(null) }
            if (client == null) LoginScreen(onLoggedIn = { client = it })
            else MainScreen(client!!)
        }
    }
}

// ---------- 登录 ----------

@Composable
fun LoginScreen(onLoggedIn: (ImClient) -> Unit) {
    var apiBase by remember { mutableStateOf(DEFAULT_API) }
    var wsBase by remember { mutableStateOf(DEFAULT_WS) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("雁书 · 登录", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(apiBase, { apiBase = it }, label = { Text("API 地址") }, modifier = Modifier.fillMaxWidth(0.6f))
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(wsBase, { wsBase = it }, label = { Text("WS 地址") }, modifier = Modifier.fillMaxWidth(0.6f))
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(username, { username = it }, label = { Text("用户名") }, singleLine = true, modifier = Modifier.fillMaxWidth(0.6f))
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(password, { password = it }, label = { Text("密码") }, singleLine = true, modifier = Modifier.fillMaxWidth(0.6f))
        error?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                enabled = !busy,
                onClick = {
                    busy = true; error = null
                    scope.launch {
                        try {
                            val client = ImClient(apiBase.trimEnd('/'), wsBase)
                            client.wireCallbacks()
                            client.login(username, password)
                            client.startSession()
                            onLoggedIn(client)
                        } catch (e: Throwable) {
                            error = e.message ?: e.toString()
                        }
                        busy = false
                    }
                },
            ) { Text(if (busy) "登录中…" else "登录") }
            OutlinedButton(
                enabled = !busy,
                onClick = {
                    busy = true; error = null
                    scope.launch {
                        try {
                            val client = ImClient(apiBase.trimEnd('/'), wsBase)
                            client.wireCallbacks()
                            client.register(username, password, username)
                            client.login(username, password)
                            client.startSession()
                            onLoggedIn(client)
                        } catch (e: Throwable) {
                            error = e.message ?: e.toString()
                        }
                        busy = false
                    }
                },
            ) { Text("注册并登录") }
        }
    }
}

// ---------- 主界面 ----------

@Composable
fun MainScreen(client: ImClient) {
    var selected by remember { mutableStateOf<Conversation?>(null) }
    val state by client.connectionState.collectAsState()
    val callState by client.callController.uiState.collectAsState()
    var showNewGroupDialog by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf("chat") }

    Row(Modifier.fillMaxSize()) {
        Column(Modifier.width(280.dp).fillMaxHeight()) {
            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(client.myNickname.ifEmpty { client.myUid.takeLast(6) }, fontWeight = FontWeight.Bold)
                Spacer(Modifier.weight(1f))
                Text(
                    when (state) {
                        is ConnState.Authenticated -> "在线"
                        is ConnState.Connecting -> "连接中"
                        else -> "离线"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (state is ConnState.Authenticated) Color(0xFF2E7D32) else Color.Gray,
                )
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { client.stop() }) { Text("退出") }
            }
            HorizontalDivider()
            // 视图切换：聊天 / 通讯录 / 朋友圈
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
                listOf("chat" to "聊天", "contacts" to "通讯录", "moments" to "朋友圈").forEach { (id, label) ->
                    FilterChip(
                        selected = tab == id,
                        onClick = {
                            tab = id
                            if (id != "chat") selected = null
                        },
                        label = { Text(label) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            HorizontalDivider()
            when (tab) {
                "contacts" -> ContactsView(client, onOpenChat = { peerUid ->
                    scopeLaunchOpenSingle(client, peerUid) { conv -> selected = conv; tab = "chat" }
                })
                "moments" -> MomentsView(client)
                else -> ConversationList(client, selected) { selected = it }
            }
        }
        VerticalDivider()
        if (selected == null) {
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                when (tab) {
                    "contacts" -> Text("选择好友开始聊天", color = Color.Gray)
                    "moments" -> Text("朋友圈", color = Color.Gray)
                    else -> Text("选择一个会话开始聊天", color = Color.Gray)
                }
            }
        } else {
            ChatScreen(client, selected!!, Modifier.weight(1f))
        }
    }

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

    if (showNewGroupDialog) {
        NewGroupDialog(
            client = client,
            onCreated = { conv -> selected = conv },
            onDismiss = { showNewGroupDialog = false },
        )
    }
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
    LazyColumn(Modifier.fillMaxSize()) {
        items(list, key = { it.id }) { conv ->
            val isSel = selected?.id == conv.id
            Surface(
                color = if (isSel) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
                onClick = { onSelect(conv) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(conv.title.ifEmpty { conv.id.takeLast(8) }, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(2.dp))
                    val unread = (conv.lastSeq - conv.readSeq).coerceAtLeast(0)
                    if (conv.lastSeq > conv.readSeq) {
                        Text("未读 $unread(conv)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            HorizontalDivider()
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

    Column(Modifier.fillMaxSize()) {
        // 头部
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(conversation.title.ifEmpty { conversation.id.takeLast(8) }, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            if (conversation.type == "single") {
                IconButton(
                    onClick = {
                        // 从会话 ID 解析对端 UID：s_{uidA}_{uidB}
                        val uids = conversation.id.removePrefix("s_").split("_")
                        val peer = uids.firstOrNull { it != client.myUid }
                        if (peer != null) client.callController.startCall(peer)
                    },
                ) { Text("📞") }
            }
        }
        HorizontalDivider()
        // 消息列表
        LazyColumn(Modifier.weight(1f), state = listState, contentPadding = PaddingValues(12.dp)) {
            items(msgs, key = { it.serverMsgId.ifEmpty { it.clientMsgId ?: "" } }) { m ->
                MessageBubble(m, mine = m.fromUid == client.myUid)
                Spacer(Modifier.height(8.dp))
            }
        }
        HorizontalDivider()
        // 输入
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            // 附件按钮
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
            ) { Text("📎") }
            OutlinedTextField(
                input, { input = it },
                placeholder = { Text("输入消息…") },
                modifier = Modifier.weight(1f),
                maxLines = 4,
            )
            Spacer(Modifier.width(8.dp))
            Button(
                enabled = input.isNotBlank(),
                onClick = {
                    val text = input
                    input = ""
                    scope.launch { client.sendMessage(conversation.id, text) }
                },
            ) { Text("发送") }
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
fun MessageBubble(m: Msg, mine: Boolean) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            Modifier
                .background(
                    if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(12.dp),
                )
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            SelectionContainer {
                Column {
                    when (m.msgType) {
                        MsgType.Image, MsgType.File, MsgType.Audio, MsgType.Video -> AttachmentView(m)
                        else -> Text(m.text, style = MaterialTheme.typography.bodyMedium)
                    }
                    Text(
                        if (m.sending) "发送中…" else timeOf(m.sentAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
            }
        }
    }
}

@Composable
fun AttachmentView(m: Msg) {
    val att = m.attachment
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
        if (att?.url?.isNotEmpty() == true) {
            TextButton(onClick = { openUrl(att.url) }) { Text("下载", style = MaterialTheme.typography.labelMedium) }
        }
    }
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / 1024.0 / 1024.0)
    bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}


private fun timeOf(ms: Long): String {
    // MVP 简单回显毫秒；后续按本地时区格式化
    return "#$ms"
}
