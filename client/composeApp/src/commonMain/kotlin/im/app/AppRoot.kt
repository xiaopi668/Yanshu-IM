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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.zIndex
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import im.client.CallMediaState
import im.client.ImClient
import im.client.auth.SavedSession
import im.client.auth.clearSession
import im.client.auth.loadSession
import im.client.auth.oidcTokenFromLaunch
import im.client.auth.saveSession
import im.client.call.CallActions
import im.client.call.CallMediaExtras
import im.client.currentTimeMillis
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
                        applySiteConfig(c)
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
                        applySiteConfig(c)
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
    /** LiveKit SFU 地址（wss://…）。为空表示服务端没配，通话不可用 */
    val livekit_url: String = "",
    /** 通行密钥是否可用（服务端配了 RP ID） */
    val passkey_enabled: Boolean = false,
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
    // 会话列表（用于把 selected 这个"快照"映射回最新数据）
    // 改备注后服务端算出的标题会变，但 selected 里存的是旧对象 ——
    // 直接用它渲染，聊天页标题就永远停在旧值上（真实反馈：改备注不生效）。
    val conversations by client.conversations.collectAsState()
    val currentConv = remember(selected, conversations) {
        selected?.let { sel -> conversations.firstOrNull { it.id == sel.id } ?: sel }
    }
    val state by client.connectionState.collectAsState()
    val callState by client.callController.uiState.collectAsState()

    // 系统通知栏的「挂断」按钮（AGENT.md 阶段 3）拿不到 CallController，也不该持有 Activity：
    // 界面层把动作登记进 CallActions，Service 侧只调 requestHangUp()。
    // key 用 client —— 退出登录/切号时会先 dispose 再重新登记，否则通知栏会调到一个
    // 已经 stop() 的客户端（点了没反应，麦克风却还开着）。
    // 这段只在登录后组合（MainScreen 就是 AppRoot 里 client != null 的那条分支）。
    DisposableEffect(client) {
        CallActions.hangUp = { client.callController.hangUp() }
        onDispose { CallActions.hangUp = null }
    }
    LaunchedEffect(callState.peerUid) {
        CallActions.peerLabel = callState.peerUid.takeLast(8)
    }

    // 通话时长从「媒体真的接通」起算，而不是从信令 InCall 起算 —— 信令通了不等于有声音
    // （AGENT.md §6.1，Web 端第一版就栽在这里）。
    // 起点放在 remember(callId) 里：换一通电话、或挂断回到 Idle（callId 变空）都会归零；
    // media 离开 Connected 时下面这个 tick 循环随即取消，不会在后台空转。
    var callStartedAtMs by remember(callState.callId) { mutableStateOf(0L) }
    var callDurationSec by remember(callState.callId) { mutableStateOf(0L) }
    LaunchedEffect(callState.callId, callState.media) {
        if (callState.media != CallMediaState.Connected) return@LaunchedEffect
        // 重连导致 Connected 重新出现时沿用同一个起点，时长不会跳回去
        val startedAt = callStartedAtMs.takeIf { it > 0L }
            ?: currentTimeMillis().also { callStartedAtMs = it }
        while (true) {
            callDurationSec = ((currentTimeMillis() - startedAt) / 1000).coerceAtLeast(0L)
            delay(1_000)
        }
    }

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
        if (currentConv == null) {
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
            ChatScreen(client, currentConv!!, Modifier.weight(1f))
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
            peer = callState.peerUid.takeLast(8),
            media = callState.media,
            error = callState.mediaError,
            muted = callState.muted,
            durationSec = callDurationSec,
            onToggleMute = { client.callController.toggleMute() },
            onHangUp = { client.callController.hangUp() },
            video = callState.video,
            videoOn = callState.videoOn,
            videoSupported = client.callController.videoSupported,
            onToggleVideo = { client.callController.toggleVideo() },
            onSwitchCamera = { client.callController.switchCamera() },
            onToggleMirror = { client.callController.toggleMirror() },
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

/**
 * 通话中界面。
 *
 * 之前这里是三行占位文字（"媒体由平台 WebRTC 承载"），实际上没有任何声音通道 ——
 * 用户看到"通话中"却听不到对方。现在按媒体面的真实状态显示，并提供静音/挂断。
 *
 * 三端共用这一份 UI，所以「免提 / 正在重连 / 去设置」这些只有 Android 才有真实实现的
 * 能力统一走 [CallMediaExtras] 这层 expect/actual：Web 与桌面拿到的是常量 false，
 * 对应控件自然不渲染；不能在 commonMain 里写 `if (isAndroid)`（KMP 没有这种开关），
 * 也不能把 AudioManager / Activity 这类 Android 专有 API 泄进共用代码。
 *
 * @param durationSec 已接通时长（秒）。由调用方从「媒体 Connected」时刻起计，
 *   状态机本身不加字段 —— 计时是表现层的事，混进 [im.client.CallUiState] 会让
 *   状态机语义（信令 + 媒体）变糊。
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun InCallOverlay(
    peer: String,
    media: CallMediaState,
    error: String,
    muted: Boolean,
    durationSec: Long,
    onToggleMute: () -> Unit,
    onHangUp: () -> Unit,
    /** 本次是否为视频通话（信令侧决定） */
    video: Boolean = false,
    /** 本端摄像头是否已打开 */
    videoOn: Boolean = false,
    /** 该端是否能真的出画面（Android / Web） */
    videoSupported: Boolean = false,
    onToggleVideo: () -> Unit = {},
    /** 切换前后摄像头（只转发给媒体面，见 CallController.switchCamera） */
    onSwitchCamera: () -> Unit = {},
    /** 本地预览镜像开关 */
    onToggleMirror: () -> Unit = {},
) {
    val scheme = MaterialTheme.colorScheme
    // 三项里只有 Android 会真的变化；Web/桌面是常量（见各端 actual）
    val speakerOn by CallMediaExtras.speakerOn.collectAsState()
    val reconnecting by CallMediaExtras.reconnecting.collectAsState()
    val micPermissionBlocked by CallMediaExtras.micPermissionBlocked.collectAsState()
    val videoBlocked by CallMediaExtras.videoBlocked.collectAsState()
    // 摄像头翻转/镜像：真实动作在媒体面，界面只读状态（docs/AGENT-camera-switch-mirror.md §4.3）。
    // 是否支持翻转是平台常量（Android/Web true、桌面 false），与 videoSupported 同源但更细。
    val cameraSwitchable = CallMediaExtras.cameraSwitchSupported
    val mirrorOn by CallMediaExtras.mirrorOn.collectAsState()
    val cameraSwitchHint by CallMediaExtras.cameraSwitchHint.collectAsState()
    // 「去设置」能不能用只有点下去才知道（openAppSettings() 拿不到宿主 Context 时返回 false）。
    // 组合期不能试调 —— 试调成功就真的跳转到设置页了。所以先按状态渲染，失败一次就隐藏，
    // 免得留下一个点了没反应的按钮。
    var settingsUnavailable by remember(peer, media) { mutableStateOf(false) }

    val (status, statusColor) = when (media) {
        // 网络切换（WiFi→4G）时 LiveKit 自己重连；此时还没接上，区别只在文案
        CallMediaState.Connecting ->
            (if (reconnecting) "正在重连…" else "正在接入语音…（如弹出麦克风授权请允许）") to scheme.inverseOnSurface
        // 注意括号：if 的优先级低于 to，不加括号会变成 if (a) x else (y to c)
        CallMediaState.Connected ->
            (if (muted) "已静音（对方听不到你）" else "已接通") to scheme.primary
        CallMediaState.Failed -> (error.ifBlank { "媒体接入失败" }) to scheme.error
        CallMediaState.Unsupported -> (error.ifBlank { "该平台暂不支持通话" }) to scheme.error
        CallMediaState.None -> "准备中…" to scheme.inverseOnSurface
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = scheme.scrim.copy(alpha = 0.6f),
        contentColor = scheme.inverseOnSurface,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 24.dp),
            ) {
                // 视频画面：Compose 画不了 <video>，这里只放占位，并在布局变化时
                // 把位置回传给平台层（浏览器端据此摆放 DOM 视频层，与人机验证小组件同款做法）
                // 只要该端能放视频就留出画面区域：对端随时可能打开摄像头，
                // 不依赖「呼叫时是否声明视频」（信令里没有这个字段）
                if (videoSupported && media == CallMediaState.Connected) {
                    // 远端画面 + 右下角本地预览。
                    // 渲染在各端的 actual 里完成：Android 是原生 SurfaceViewRenderer，
                    // Web 是 DOM 图层（位置由它自己回传），桌面是占位。
                    Box(Modifier.fillMaxWidth().height(220.dp)) {
                        CallVideoSurface(
                            role = CallVideoRole.Remote,
                            modifier = Modifier
                                .fillMaxSize()
                                // 圆角只作用在背景层：Android 的 SurfaceView 不能被裁剪（会黑屏）
                                .clip(MaterialTheme.shapes.large),
                        )
                        CallVideoSurface(
                            role = CallVideoRole.Local,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(8.dp)
                                .size(96.dp)
                                .clip(MaterialTheme.shapes.medium),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }

                Text(if (peer.isBlank()) "通话中" else "与 $peer 通话中", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text(status, style = MaterialTheme.typography.bodySmall, color = statusColor)

                // 时长只在接通后才有意义；等宽字体是为了秒数跳动时数字不左右抖
                if (media == CallMediaState.Connected || durationSec > 0L) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        formatCallDuration(durationSec),
                        style = MaterialTheme.typography.titleMedium,
                        fontFamily = FontFamily.Monospace,
                        color = scheme.inverseOnSurface,
                    )
                }

                // 摄像头权限被拒：通话本身照常（只是没画面），但必须让用户知道为什么黑屏，
                // 以及怎么修 —— 静默黑屏是最难自查的一种失败。
                if (videoBlocked) {
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "摄像头权限被拒绝，对方看不到你的画面（可点下方「去设置」开启）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }

                // 「翻转」为什么点不动：只有一颗摄像头的设备上必须说明，不能静默无效
                // （文案由媒体面写入 CallMediaExtras.cameraSwitchHint，界面只管展示）
                if (videoSupported && cameraSwitchHint.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        cameraSwitchHint,
                        style = MaterialTheme.typography.labelSmall,
                        color = scheme.inverseOnSurface.copy(alpha = 0.8f),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }

                // 阶段 5：权限被拒是唯一「用户自己能修」的失败，给一条出路；
                // 其它失败（token 过期 / 地址不可达）没有可操作项，只显示原因。
                if ((media == CallMediaState.Failed && micPermissionBlocked) || videoBlocked) {
                    Spacer(Modifier.height(16.dp))
                    FilledTonalButton(onClick = {
                        if (!CallMediaExtras.openAppSettings()) settingsUnavailable = true
                    }) { Text("去设置") }
                }

                if (media == CallMediaState.Connecting) {
                    Spacer(Modifier.height(16.dp))
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }

                Spacer(Modifier.height(24.dp))
                // 用 FlowRow 而不是 Row：这一行现在最多有 6 个按钮（免提/静音/摄像头/翻转/镜像/挂断），
                // 窄屏上单行必然溢出 —— 溢出不是「被裁掉」，而是把「挂断」挤出屏幕之外。
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    // 免提：只有该平台真的能切音频路由时才出现（Web 由浏览器决定，桌面没有媒体面）
                    if (CallMediaExtras.speakerSupported) {
                        FilledTonalButton(
                            onClick = { CallMediaExtras.toggleSpeaker() },
                            // 没接通时切路由没有意义，也避免和入会时的默认路由打架
                            enabled = media == CallMediaState.Connected,
                        ) {
                            // 文案是「点了会切到哪儿」，不是「现在在哪儿」——
                            // 和系统电话里的免提键一致，免得用户以为它是个状态指示
                            Text(if (speakerOn) "听筒" else "免提")
                        }
                    }
                    // 静音只在真正接通后有意义
                    FilledTonalButton(
                        onClick = onToggleMute,
                        enabled = media == CallMediaState.Connected,
                    ) {
                        Text(if (muted) "取消静音" else "静音")
                    }
                    // 摄像头开关：只有能出画面的平台才显示
                    if (videoSupported) {
                        FilledTonalButton(
                            onClick = onToggleVideo,
                            enabled = media == CallMediaState.Connected,
                        ) {
                            Text(if (videoOn) "关摄像头" else "开摄像头")
                        }
                    }
                    // 翻转前后摄像头。三个前置条件缺一不可：
                    // - 该端真的支持切换（桌面不算，文档 §6.7：不要假装支持）
                    // - 已经接通、且本地预览开着（没开摄像头时切换是无操作）
                    // - 没有「不可切换」的原因（只有一颗摄像头时置灰 + 上方文案说明）
                    if (videoSupported && cameraSwitchable) {
                        FilledTonalButton(
                            onClick = onSwitchCamera,
                            enabled = media == CallMediaState.Connected &&
                                videoOn && cameraSwitchHint.isBlank(),
                        ) {
                            Text("翻转")
                        }
                    }
                    // 镜像只影响本地预览小窗（发布出去的流不镜像，否则对方看到的字是反的），
                    // 所以只在本地画面可见时出现。文案按「点了会变成什么」写，与免提键一致。
                    if (videoSupported && videoOn && !videoBlocked) {
                        FilledTonalButton(
                            onClick = onToggleMirror,
                            enabled = media == CallMediaState.Connected,
                        ) {
                            Text(if (mirrorOn) "取消镜像" else "镜像")
                        }
                    }
                    Button(
                        onClick = onHangUp,
                        colors = ButtonDefaults.buttonColors(containerColor = scheme.error, contentColor = scheme.onError),
                    ) { Text("挂断") }
                }
            }
        }
    }
}

/**
 * 通话时长文案：不足 1 小时 `mm:ss`，超过 1 小时 `h:mm:ss`（和微信/系统电话一致）。
 *
 * 手写补齐而不是 String.format —— wasm 目标没有 java.util.Formatter，
 * 本文件已经因为同类问题（formatSize）踩过一次。
 */
internal fun formatCallDuration(totalSeconds: Long): String {
    val s = totalSeconds.coerceAtLeast(0L)
    val hours = s / 3600
    val minutes = (s % 3600) / 60
    val seconds = s % 60
    fun two(v: Long): String = if (v < 10) "0$v" else v.toString()
    return if (hours > 0) "$hours:${two(minutes)}:${two(seconds)}" else "${two(minutes)}:${two(seconds)}"
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

    // 进入会话先补历史：刷新页面后本地缓存是空的，不主动拉就只剩空白
    //（服务端搜索能搜到，聊天页却没有 —— 用户反馈过这个割裂现象）
    LaunchedEffect(conversation.id) {
        runCatching { client.loadHistory(conversation.id) }
    }

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
                            // 语音通话：视频由对方或本方在通话中按需开启
                            if (peer != null) client.callController.startCall(peer, video = false)
                        },
                    ) {
                        Text(
                            "📞",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.semantics { contentDescription = "语音通话" },
                        )
                    }
                    // 视频通话：只有能出画面的平台才显示。呼叫即开摄像头，
                    // 对端接听后一旦订阅到视频轨就会渲染（信令里不带视频标记）
                    if (client.callController.videoSupported) {
                        IconButton(
                            onClick = {
                                val uids = conversation.id.removePrefix("s_").split("_")
                                val peer = uids.firstOrNull { it != client.myUid }
                                if (peer != null) client.callController.startCall(peer, video = true)
                            },
                        ) {
                            Text(
                                "📹",
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.semantics { contentDescription = "视频通话" },
                            )
                        }
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
                        // UNDISPATCHED：协程体在点击事件的同一个调用栈里立即执行，
                        // 否则 input.click() 落到下一个调度周期，浏览器判定不是用户手势、
                        // 直接忽略 —— 表现就是「点📎没反应、附件传不上去」。
                        scope.launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
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
                // 发送动作抽出来，回车与按钮走同一条路径
                val doSend = {
                    if (input.isNotBlank()) {
                        val text = input
                        input = ""
                        scope.launch { client.sendMessage(conversation.id, text) }
                    }
                    Unit
                }
                TextField(
                    input, { input = it },
                    placeholder = { Text("输入消息…（Enter 发送，Ctrl+Enter 换行）") },
                    modifier = Modifier.weight(1f)
                        // 键盘习惯：Enter 发送、Ctrl/Shift/Meta+Enter 换行。
                        // 用 onPreviewKeyEvent 在 TextField 处理之前拦下来，
                        // 否则回车会先被插入成换行；返回 true 表示事件已被消费。
                        .onPreviewKeyEvent { e ->
                            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                            val isEnter = e.key == Key.Enter || e.key == Key.NumPadEnter
                            if (!isEnter) return@onPreviewKeyEvent false
                            if (e.isCtrlPressed || e.isShiftPressed || e.isMetaPressed) {
                                input += "\n"
                                true
                            } else {
                                doSend()
                                true
                            }
                        },
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
                    onClick = { doSend() },
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
    // att.url 现在是对象 key，不是可直接打开的地址：打开前用登录态换一张短时票据，
    // 这样消息体里不会出现长期有效的登录 JWT。
    val attKey = att?.url?.takeIf { it.isNotEmpty() }
    val label = when (m.msgType) {
        MsgType.Image -> att?.name ?: m.text
        MsgType.File -> "📄 ${att?.name ?: m.text}"
        MsgType.Audio -> "🎤 ${att?.name ?: m.text}"
        MsgType.Video -> "🎬 ${att?.name ?: m.text}"
        else -> m.text
    }
    Column {
        // 图片直接内联渲染：点一下看原图。
        // 缩略图固定 200dp + Crop，避免不同长宽比的图片把消息列表撑得参差不齐。
        if (m.msgType == MsgType.Image && attKey != null) {
            Box(
                Modifier
                    .size(200.dp)
                    .clip(MaterialTheme.shapes.medium)
                    .clickable {
                        scope.launch {
                            runCatching { openUrl(client.api.attachmentUrl(client.myToken, attKey)) }
                        }
                    },
            ) {
                NetImage(client.api, client.myToken, attKey, Modifier.fillMaxSize())
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f, fill = false),
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                att?.size?.let {
                    Text(
                        "  " + formatSize(it),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
            }
            TextButton(onClick = {
                scope.launch { runCatching { openUrl(client.api.attachmentUrl(client.myToken, attKey)) } }
            }) { Text("查看原图", style = MaterialTheme.typography.labelMedium) }
            return@Column
        }
        Text(label, style = MaterialTheme.typography.bodyMedium)
        att?.size?.let {
            Text(
                formatSize(it),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
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

/**
 * 拉一次站点配置，把通话地址注入客户端。
 *
 * 之前只有登录页（密码登录 / 注册 / OIDC 手工粘贴）会给 callController.livekitUrl 赋值，
 * 而「OIDC 自动回调」和「本地会话恢复」这两条路径根本不经过登录页 ——
 * 结果老用户（有本地 token）一进站就永远提示「服务端未配置通话地址（livekit_url）」，
 * 哪怕服务端 /v1/site-config 里明明配了 wss://。
 *
 * 拉取失败（离线 / 服务端异常）时静默跳过：通话不可用，但不该阻断登录。
 */
private suspend fun applySiteConfig(c: ImClient) {
    val url = runCatching { apiSiteConfig(c.apiBaseUrl).livekit_url }.getOrNull()
    if (!url.isNullOrBlank()) c.callController.livekitUrl = url
}
