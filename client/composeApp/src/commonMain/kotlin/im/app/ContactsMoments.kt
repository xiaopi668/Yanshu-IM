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
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
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
                var menuOpen by remember { mutableStateOf(false) }
                var editRemark by remember { mutableStateOf(false) }
                var confirmDelete by remember { mutableStateOf(false) }
                Row(
                    Modifier.fillMaxWidth().clickable {
                        onOpenChat(c.uid)
                    }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(c.remark.ifEmpty { c.nickname }, fontWeight = FontWeight.SemiBold)
                        Text(
                            "雁书号: ${c.yid.ifEmpty { "-" }}",
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
                if (editRemark) {
                    var remark by remember { mutableStateOf(c.remark) }
                    AlertDialog(
                        onDismissRequest = { editRemark = false },
                        title = { Text("设置备注") },
                        text = { OutlinedTextField(remark, { remark = it }, singleLine = true) },
                        confirmButton = {
                            TextButton(onClick = {
                                scope.launch { client.api.setRemark(client.myToken, c.uid, remark); editRemark = false; reload() }
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

// ============ 个人资料 ============

@Composable
fun ProfileDialog(client: ImClient, onDismiss: () -> Unit) {
    var nickname by remember { mutableStateOf(client.myNickname) }
    var yid by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var yidChanged by remember { mutableStateOf(true) }
    var msg by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    var avatarKey by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        try {
            val me = client.api.me(client.myToken)
            nickname = me.nickname
            yid = me.yid
            username = me.username
            yidChanged = me.yid_changed
            avatarKey = me.avatar
        } catch (_: Throwable) {}
    }
    val avatarUrl = if (avatarKey.isBlank()) null else client.api.downloadUrl(client.myToken, avatarKey)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("我的资料") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                // 头像
                if (avatarUrl != null) {
                    NetImage(url = avatarUrl, modifier = Modifier.size(64.dp).clip(RoundedCornerShape(32.dp)))
                } else {
                    Box(
                        Modifier.size(64.dp).clip(RoundedCornerShape(32.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center,
                    ) { Text(nickname.take(1), style = MaterialTheme.typography.headlineSmall) }
                }
                TextButton(onClick = {
                    scope.launch {
                        try {
                            val f = im.client.file.pickFile() ?: return@launch
                            val key = client.api.uploadAttachment(client.myToken, "image", f.bytes)
                            client.api.setAvatar(client.myToken, key)
                            avatarKey = key
                        } catch (e: Throwable) { msg = e.message }
                    }
                }) { Text("更换头像") }
                Spacer(Modifier.height(4.dp))
                Text("用户名：$username", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("雁书号：$yid", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!yidChanged) {
                    Text("可修改一次", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(nickname, { nickname = it }, label = { Text("昵称") }, singleLine = true)
                if (!yidChanged) {
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(yid, { yid = it }, label = { Text("新雁书号") }, singleLine = true)
                }
                msg?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall) }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    scope.launch {
                        try {
                            client.api.updateNickname(client.myToken, nickname.trim())
                            if (!yidChanged && yid != "") {
                                client.api.changeYid(client.myToken, yid.trim())
                                yidChanged = true
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
                        Column(
                            Modifier.fillMaxWidth().clickable { onJump(h.conversation_id) }.padding(vertical = 6.dp),
                        ) {
                            Text(h.text, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                            Text(
                                "${h.conversation_id.takeLast(8)} · ${momentTimeOf(h.sent_at)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (searched && results.isEmpty()) {
                        item { Text("无结果", color = Color.Gray) }
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
                Text("成员 (${members.size})", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(vertical = 6.dp))
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
                Spacer(Modifier.height(6.dp))
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
