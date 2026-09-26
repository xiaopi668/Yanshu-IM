package im.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import im.client.ImClient
import im.client.api.acceptContact
import im.client.api.contactRequests
import im.client.api.commentMoment
import im.client.api.contacts
import im.client.api.createMoment
import im.client.api.deleteMoment
import im.client.api.likeMoment
import im.client.api.momentFeed
import im.client.api.rejectContact
import im.client.api.requestContact
import im.client.api.searchByYid
import im.client.api.unlikeMoment
import androidx.compose.ui.text.font.FontWeight
import im.client.api.ContactRequestResp
import im.client.api.ContactResp
import im.client.api.MomentResp
import im.client.openUrl
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
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
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
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 12.dp)) }

        LazyColumn(Modifier.weight(1f)) {
            // 好友申请
            if (requests.any { it.status == "pending" }) {
                item {
                    Text(
                        "新的朋友",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                    )
                }
                items(requests.filter { it.status == "pending" }, key = { it.id }) { req ->
                    Card(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text("${req.nickname} (${req.yid})", fontWeight = FontWeight.SemiBold)
                            if (req.message.isNotBlank()) {
                                Text(req.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                )
            }
            items(contacts, key = { it.uid }) { c ->
                Row(
                    Modifier.fillMaxWidth().clickable {
                        onOpenChat(c.uid)
                    }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(c.remark.ifEmpty { c.nickname }, fontWeight = FontWeight.SemiBold)
                        Text(
                            "雁书号: ${c.yid.ifEmpty { "-" }}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
                        Text("还没有动态，点击右下角发布", color = Color.Gray)
                    }
                }
            }
        }
        if (error != null) {
            Text(
                error!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
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
        Column(Modifier.padding(12.dp)) {
            Text(moment.nickname, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
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
                                val url = client.api.downloadUrl(client.myToken, key)
                                NetImage(
                                    url = url,
                                    modifier = Modifier.size(96.dp).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                                )
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
                            modifier = Modifier.padding(vertical = 2.dp),
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
                            scope.launch {
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

// 简易网络图片（字节加载 → ImageBitmap）
@Composable
fun NetImage(url: String, modifier: Modifier = Modifier) {
    var bmp by remember(url) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var failed by remember(url) { mutableStateOf(false) }
    LaunchedEffect(url) {
        try {
            val (code, data) = im.client.api.Http.getBinary(url)
            if (code in 200..299) {
                bmp = org.jetbrains.skia.Image.makeFromEncoded(data).toComposeImageBitmap()
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
                modifier = Modifier.fillMaxSize(),
            )
        } else if (failed) {
            Text("🖼", color = Color.Gray)
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


internal fun momentTimeOf(ms: Long): String = "#$ms"
