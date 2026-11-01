package im.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import org.jetbrains.compose.resources.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import im.client.ImClient
import im.client.auth.OidcLoginResult
import im.client.auth.SavedSession
import im.client.auth.oidcLogin
import im.client.auth.saveSession
import im.client.openUrl
import im.app.resources.Res
import im.app.resources.logo
import im.client.registerTurnstileCallback
import im.client.hideTurnstileWidget
import im.client.positionTurnstileWidget
import im.client.registerTurnstileErrorCallback
import im.client.renderTurnstileWidget
import im.client.wireCallbacks
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 登录页：品牌头 + 卡片表单。
 * 站点配置（注册开关 / 人机验证 / 邮箱验证码 / OIDC）决定卡片里长什么样。
 */
@Composable
fun LoginScreen(onLoggedIn: (ImClient) -> Unit) {
    // 人机验证是 DOM 节点，不随 Compose 一起销毁。
    // 登录成功后整个登录页会被移出组合，此时必须主动把它收起来 ——
    // 否则它会一直飘在主界面上（之前就是这样）。
    DisposableEffect(Unit) {
        onDispose { hideTurnstileWidget("turnstile-box") }
    }
    var apiBase by remember { mutableStateOf(DEFAULT_API) }
    var wsBase by remember { mutableStateOf(DEFAULT_WS) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var emailCode by remember { mutableStateOf("") }
    // 发送验证码后的冷却：服务端本身有频率限制，但界面上没有任何反馈，
    // 用户只会反复点（然后吃到 429）。这里做成 60 秒倒计时，顺带给出明确预期。
    var codeCooldown by remember { mutableStateOf(0) }
    var showResetDialog by remember { mutableStateOf(false) }
    LaunchedEffect(codeCooldown) {
        if (codeCooldown > 0) {
            delay(1000)
            codeCooldown -= 1
        }
    }
    var showPassword by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var tip by remember { mutableStateOf<String?>(null) }
    var siteCfg by remember { mutableStateOf<SiteConfigResp?>(null) }
    var siteCfgErr by remember { mutableStateOf<String?>(null) }
    var turnstileToken by remember { mutableStateOf("") }
    // 当前端能否真正渲染 Turnstile：Web 端能，桌面/Android 是桩实现（返回 false）
    var turnstileRenderable by remember { mutableStateOf(false) }
    // 人机验证的失败原因（脚本被拦、密钥不匹配、超时…）：不显示出来用户只会看到一直转圈
    var turnstileErr by remember { mutableStateOf<String?>(null) }
    var showConn by remember { mutableStateOf(false) }
    // 登录 / 注册是两个独立界面：字段、按钮、校验要求都不一样。
    // 之前挤在同一张卡片里，既容易误点，也让「注册需要邮箱验证码」这类要求看不清。
    var isRegister by remember { mutableStateOf(false) }
    var oidcToken by remember { mutableStateOf("") }
    // 自动回调失败时才提示手工粘贴；成功路径下这个输入框根本用不上
    var oidcManual by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    /** 拿到 OIDC 令牌后的统一登录动作：自动回调与手工粘贴共用 */
    suspend fun finishOidcLogin(token: String) {
        try {
            val c = ImClient(apiBase.trimEnd('/'), wsBase)
            c.callController.livekitUrl = siteCfg?.livekit_url ?: ""
            c.wireCallbacks()
            c.loginWithToken(token)
            c.startSession()
            saveSession(SavedSession(c.myToken, c.apiBaseUrl, c.gatewayWsUrl))
            onLoggedIn(c)
        } catch (e: Throwable) {
            error = e.message ?: e.toString()
        }
    }

    // 站点配置跟随 API 地址：改地址必须重新拉。
    // 之前只在首次组合时拉一次、异常静默吞掉 —— 改完 API 地址后注册开关 / 人机验证 /
    // 邮箱验证码 / OIDC 按钮就全部不再显示，页面看起来「什么都没有」。
    LaunchedEffect(apiBase.trimEnd('/')) {
        siteCfg = null
        siteCfgErr = null
        turnstileToken = ""
        turnstileRenderable = false
        delay(400) // 输入过程中防抖
        try {
            val cfg = apiSiteConfig(apiBase.trimEnd('/'))
            siteCfg = cfg
            if (cfg.turnstile_enabled && cfg.turnstile_site_key.isNotEmpty()) {
                registerTurnstileCallback { token ->
                    turnstileToken = token
                    turnstileErr = null
                }
                registerTurnstileErrorCallback { msg -> turnstileErr = msg }
                turnstileRenderable = renderTurnstileWidget(cfg.turnstile_site_key, "turnstile-box")
            }
        } catch (e: Throwable) {
            siteCfgErr = "站点配置加载失败：${e.message ?: e}（请确认 API 地址指向服务端 :10002）"
        }
    }
    // 站点关闭注册时不给注册入口
    val canRegister = siteCfg?.registration_enabled != false
    val turnstileOn = siteCfg?.turnstile_enabled == true && siteCfg?.turnstile_site_key?.isNotEmpty() == true
    // 站点开了人机验证却没拿到令牌：登录/注册都会被服务端 403，提前拦掉并说明原因
    val turnstilePending = turnstileOn && turnstileToken.isEmpty()
    // 当前端根本渲染不出小组件（桌面/Android）时，光等着也没用，要给出替代方案
    val turnstileBlocked = turnstilePending && !turnstileRenderable

    // 原写法：Box(Modifier.fillMaxSize().background(colorScheme.background)) 自绘页面底色。
    // 现在：M3 Scaffold 作为页面容器，底色由 containerColor 提供。
    Scaffold(containerColor = MaterialTheme.colorScheme.background) { inner ->
    Column(
        Modifier.fillMaxSize()
            .padding(inner)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
            Spacer(Modifier.height(48.dp))
            BrandHeader()
            Spacer(Modifier.height(24.dp))
            Surface(
                // Material You：大圆角 + tonal 表面（靠底色层级表达高度，不用投影）
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier.widthIn(max = 440.dp).fillMaxWidth(),
            ) {
                Column(Modifier.padding(horizontal = 32.dp, vertical = 32.dp)) {
                    Text(
                        if (isRegister) "注册" else "登录",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (isRegister) "创建一个新账号" else "用户名 / 雁书号 / 邮箱 + 密码，或使用下方第三方账号",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(24.dp))

                    AuthField(
                        username, { username = it },
                        if (isRegister) "用户名（登录名）" else "用户名 / 雁书号 / 邮箱",
                    )
                    Spacer(Modifier.height(16.dp))
                    AuthField(
                        password, { password = it }, "密码",
                        password = !showPassword,
                        trailing = {
                            // 原写法：Text + clip + clickable + 手动 padding（没有涟漪）。
                            // 现在：TextButton —— 自带涟漪、无障碍语义与最小触控区。
                            TextButton(onClick = { showPassword = !showPassword }) {
                                Text(
                                    if (showPassword) "隐藏" else "显示",
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        },
                    )

                    // 邮箱只在注册界面出现（服务端无论是否开验证码都会保存）；验证码相关只在站点开启后出现
                    if (isRegister && canRegister) {
                        Spacer(Modifier.height(16.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) {
                                // 标签必须跟随站点要求：开了邮箱验证码就是必填，
                                // 还写「可留空」会让用户填完了才被服务端打回（截图里的那种情况）
                                AuthField(
                                    email, { email = it },
                                    if (siteCfg?.email_code_enabled == true) "邮箱（必填，需收验证码）"
                                    else "邮箱（选填，可用于登录）",
                                )
                            }
                            if (siteCfg?.email_code_enabled == true) {
                                Spacer(Modifier.width(8.dp))
                                OutlinedButton(
                                    enabled = email.contains("@") && !busy && codeCooldown == 0,
                                    onClick = {
                                        scope.launch {
                                            try {
                                                val ok = apiSendEmailCode(apiBase.trimEnd('/'), email, turnstileToken)
                                                if (ok) {
                                                    tip = "验证码已发送（5 分钟内有效）"
                                                    // 冷却 60s：既防连点，也把「5 分钟有效」的预期讲清楚
                                                    codeCooldown = 60
                                                }
                                            } catch (e: Throwable) {
                                                error = e.message ?: e.toString()
                                            }
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp),
                                ) {
                                    Text(
                                        if (codeCooldown > 0) codeCooldown.toString() + " 秒后重发" else "发送验证码",
                                        style = MaterialTheme.typography.labelMedium,
                                    )
                                }
                            }
                        }
                        if (siteCfg?.email_code_enabled == true) {
                            Spacer(Modifier.height(16.dp))
                            AuthField(emailCode, { emailCode = it }, "邮箱验证码（必填）")
                            Spacer(Modifier.height(8.dp))
                            Text(
                                if (email.isBlank()) "本站要求邮箱验证码：请先填邮箱并点「发送验证码」"
                                else "请输入邮箱里收到的验证码（5 分钟内有效）",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (email.isBlank()) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else if (isRegister) {
                        // 之前这里什么都不显示：注册被关闭时整个注册区（含邮箱框）静默消失，
                        // 用户只会以为「注册功能没了 / 找不到填邮箱的地方」。
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "本站已关闭注册。管理员可在管理后台「认证设置」里勾选「允许注册」后重试。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // 站点配置没拉到时给个明确提示，否则用户只会看到「这些区域凭空消失」
                    siteCfgErr?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }

                    // Turnstile：小组件由各平台 actual 负责渲染（只有 Web 端真的画得出来）。
                    // **登录和注册都要显示** —— 服务端在 /v1/login 与 /v1/register 两处都会校验
                    // （main.go 的 checkTurnstile），只在注册界面放会让登录既提交不了也没处验证。
                    if (turnstileOn) {
                        TurnstileSlot(turnstileToken, turnstileRenderable, turnstileErr)
                    }
                    // 站点关掉人机验证时才隐藏小组件：它是个 DOM 节点，不藏会留在页面上
                    LaunchedEffect(turnstileOn) {
                        if (!turnstileOn) hideTurnstileWidget("turnstile-box")
                    }

                    error?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    tip?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                    }

                    Spacer(Modifier.height(24.dp))
                    if (!isRegister) Button(
                        // 站点开了人机验证时，没拿到令牌就禁用：服务端 /v1/login 会校验它，
                        // 让用户点下去只会得到一句「请完成人机验证」而无从下手。
                        enabled = !busy && !turnstilePending,
                        onClick = {
                            busy = true; error = null; tip = null
                            scope.launch {
                                try {
                                    val client = ImClient(apiBase.trimEnd('/'), wsBase)
                                    client.callController.livekitUrl = siteCfg?.livekit_url ?: ""
                                    client.wireCallbacks()
                                    client.login(username, password, turnstileToken)
                                    client.startSession()
                                    saveSession(SavedSession(client.myToken, client.apiBaseUrl, client.gatewayWsUrl))
                                    onLoggedIn(client)
                                } catch (e: Throwable) {
                                    error = e.message ?: e.toString()
                                }
                                busy = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = CircleShape,
                    ) { Text(if (busy) "登录中…" else "登录", style = MaterialTheme.typography.titleSmall) }

                    // 忘记密码：站点没开邮箱服务时没有任何可自助的通道，
                    // 直接说明「找管理员」，比给一个点了就报错的入口好
                    if (!isRegister) {
                        if (siteCfg?.email_code_enabled == true) {
                            TextButton(onClick = { showResetDialog = true }) {
                                Text("忘记密码？", style = MaterialTheme.typography.labelLarge)
                            }
                        } else {
                            Text(
                                "忘记密码请联系管理员重置（本站未开启邮箱验证）",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    if (isRegister && canRegister) {
                        OutlinedButton(
                            enabled = !busy && !turnstilePending && username.isNotBlank() && password.isNotBlank(),
                            onClick = {
                                // 先本地校验：站点要求邮箱验证时，缺邮箱或验证码直接提示，
                                // 不发一个必然 400 的请求再拿服务端文案回来
                                if (siteCfg?.email_code_enabled == true &&
                                    (email.isBlank() || emailCode.isBlank())
                                ) {
                                    error = "本站要求邮箱验证：请填写邮箱并填入收到的验证码"
                                    return@OutlinedButton
                                }
                                busy = true; error = null; tip = null
                                scope.launch {
                                    try {
                                        val client = ImClient(apiBase.trimEnd('/'), wsBase)
                                        client.callController.livekitUrl = siteCfg?.livekit_url ?: ""
                                        client.wireCallbacks()
                                        // 注册接口本身就返回令牌，直接用它登录。
                                        // 不能再调一次 login：Turnstile 令牌是一次性的，
                                        // 注册已经把它用掉了，第二次校验必然失败
                                        //（Cloudflare 会返回 timeout-or-duplicate）。
                                        val (_, regToken) = client.register(
                                            username, password, username, "", email, emailCode, turnstileToken,
                                        )
                                        client.loginWithToken(regToken)
                                        client.startSession()
                                        saveSession(SavedSession(client.myToken, client.apiBaseUrl, client.gatewayWsUrl))
                                        onLoggedIn(client)
                                    } catch (e: Throwable) {
                                        error = e.message ?: e.toString()
                                    }
                                    busy = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = CircleShape,
                        ) { Text("注册并登录", style = MaterialTheme.typography.titleSmall) }
                    }

                    // 登录 / 注册切换。站点关闭注册时不提供「去注册」，
                    // 但仍要能退回登录，否则会卡在注册界面。
                    if (canRegister || isRegister) {
                        Spacer(Modifier.height(8.dp))
                        // 原写法：Text + clip + clickable。现在：TextButton（自带涟漪与语义）
                        TextButton(
                            onClick = {
                                isRegister = !isRegister
                                error = null
                                tip = null
                                oidcManual = false
                            },
                        ) {
                            Text(
                                if (isRegister) "已有账号？去登录" else "还没有账号？去注册",
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }

                    // 第三方登录
                    siteCfg?.oidc_providers?.takeIf { it.isNotEmpty() }?.let { providers ->
                        Spacer(Modifier.height(24.dp))
                        OrDivider()
                        Spacer(Modifier.height(16.dp))
                        providers.forEach { p ->
                            OutlinedButton(
                                onClick = {
                                    scope.launch {
                                        busy = true
                                        error = null
                                        tip = null
                                        try {
                                            when (val r = oidcLogin(p.authorize_url, 5 * 60_000)) {
                                                // 桌面：本地回环监听器已经拿到令牌
                                                // Android：deep link 把令牌交回 App
                                                is OidcLoginResult.Token -> finishOidcLogin(r.token)
                                                // Web：页面正在跳去授权方，回来后由 AppRoot 消费 fragment
                                                OidcLoginResult.Redirecting -> Unit
                                                // 拿不到自动回调才退回手工粘贴
                                                is OidcLoginResult.Manual -> {
                                                    oidcManual = true
                                                    tip = r.reason
                                                    openUrl(p.authorize_url)
                                                }
                                            }
                                        } catch (e: Throwable) {
                                            error = e.message ?: e.toString()
                                        } finally {
                                            busy = false
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = CircleShape,
                            ) { Text("使用 ${p.name} 登录") }
                        }
                        if (oidcManual) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "自动回跳不可用，请从授权成功页复制令牌粘贴到下方（管理员可在服务端配置 " +
                                    "IM_OIDC_RETURN_ALLOWLIST 打开自动回跳）",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))
                            AuthField(oidcToken, { oidcToken = it }, "OIDC Token")
                            Spacer(Modifier.height(8.dp))
                            FilledTonalButton(
                                enabled = oidcToken.isNotBlank() && !busy,
                                onClick = {
                                    scope.launch {
                                        busy = true
                                        try {
                                            finishOidcLogin(oidcToken.trim())
                                        } finally {
                                            busy = false
                                        }
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = CircleShape,
                            ) { Text("完成 OIDC 登录") }
                        }
                    }

                    // 构建期可用 -Pim.hideServer=true 整体隐藏：私有化部署时终端用户
                    // 既不需要、也不应该看到服务端地址。
                    if (!ImBuildConfig.HIDE_SERVER_CONFIG) {
                        // 服务器地址默认收起，避免和主表单抢视线；改了会自动重新拉站点配置
                        Spacer(Modifier.height(16.dp))
                        // 原写法：Text + clip + clickable。现在：TextButton
                        TextButton(onClick = { showConn = !showConn }) {
                            Text(
                                if (showConn) "收起服务器地址" else "服务器地址（$apiBase）",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (showConn) {
                            Spacer(Modifier.height(8.dp))
                            AuthField(apiBase, { apiBase = it }, "API 地址")
                            Spacer(Modifier.height(16.dp))
                            AuthField(wsBase, { wsBase = it }, "WS 地址")
                        }
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
    }
    }
    // 忘记密码弹窗挂在滚动容器之外：放在卡片里会被滚动区域裁掉
    if (showResetDialog && siteCfg?.email_code_enabled == true) {
        ResetPasswordDialog(
            apiBase = apiBase.trimEnd('/'),
            turnstileToken = turnstileToken,
            onDismiss = { showResetDialog = false },
            onDone = {
                showResetDialog = false
                tip = "密码已重置，请用新密码登录"
                error = null
            },
        )
    }
}

/** 品牌标识：圆角方块 + 名称 + 一句话说明 */
@Composable
private fun BrandHeader() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // 品牌图标用真实图片资源（此前是代码画的圆角色块 + 「雁」字）
        androidx.compose.foundation.Image(
            painter = painterResource(Res.drawable.logo),
            contentDescription = "雁书",
            modifier = Modifier.size(56.dp),
        )
        Spacer(Modifier.width(16.dp))
        Column {
            Text("雁书", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
                "私有化即时通讯",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 统一样式的输入框：单行、圆角、聚焦主色 */
@Composable
private fun AuthField(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    password: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value, onChange,
        label = { Text(label) },
        singleLine = true,
        maxLines = 1,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        trailingIcon = trailing,
        shape = MaterialTheme.shapes.extraSmall,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** “或使用其他方式” 分隔线 */
@Composable
private fun OrDivider() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(modifier = Modifier.weight(1f))
        Text(
            " 或 ",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        HorizontalDivider(modifier = Modifier.weight(1f))
    }
}

/**
 * 人机验证占位区。
 * 三种状态分开说：通过 / 平台不支持 / 组件没加载出来，避免一直卡在「加载中」。
 */
@Composable
private fun TurnstileSlot(token: String, renderable: Boolean, err: String? = null) {
    var timeout by remember { mutableStateOf(false) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    LaunchedEffect(token) {
        timeout = false
        if (token.isNotEmpty()) return@LaunchedEffect
        // 20s 而不是 10s：Cloudflare 脚本在弱网下经常要十几秒才就绪，
        // 抢在它前面报"加载失败"只会误导用户（之前就是这样：组件其实已经加载出来了）
        delay(20_000)
        if (token.isEmpty()) timeout = true
    }
    Spacer(Modifier.height(16.dp))
    // 人机验证是 DOM 浮层（Web 端 Compose 画在 canvas 上，无法嵌套 DOM），
    // 位置只能由这里回传：否则它会固定停在窗口某个位置、盖住别的元素。
    LaunchedEffect(token) {
        if (token.isNotEmpty()) hideTurnstileWidget("turnstile-box")
    }
    // 原写法：Box + clip(shapes.large) + background(surfaceContainerHighest) 自绘底色。
    // 现在：Surface 提供形状与 tonal 底色（Surface 负责裁剪内容）。
    Surface(
        modifier = Modifier.fillMaxWidth().height(72.dp)
            .onGloballyPositioned { coords ->
                // boundsInWindow 给的是 Compose px（= CSS px × devicePixelRatio），
                // 而 DOM 样式用的是 CSS px。HiDPI（浏览器缩放 2 倍）下不换算，
                // 小组件会被摆到右下角 —— 之前就是这样错位的。
                val d = density.density
                val b = coords.boundsInWindow()
                positionTurnstileWidget(
                    "turnstile-box",
                    (b.left / d).toInt(), (b.top / d).toInt(),
                    (b.width / d).toInt(), (b.height / d).toInt(),
                )
            },
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val (msg, color) = when {
            token.isNotEmpty() -> "✓ 人机验证通过" to MaterialTheme.colorScheme.primary
            // 有具体原因就先说原因（错误码 / 域名不匹配 / 网络不通），比笼统的"加载失败"有用得多
            err != null -> err to MaterialTheme.colorScheme.error
            !renderable -> "人机验证仅 Web 端支持：桌面/Android 请改用浏览器注册，或在管理后台关闭「人机验证」" to MaterialTheme.colorScheme.error
            timeout -> "人机验证组件加载失败（网络或 CSP 拦截了 challenges.cloudflare.com）" to MaterialTheme.colorScheme.error
                else -> "请在页面下方完成人机验证" to MaterialTheme.colorScheme.onSurfaceVariant
            }
            Text(
                msg,
                color = color,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 16.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

/**
 * 忘记密码：邮箱验证码重置。
 *
 * 只在站点开启邮箱验证时可用 —— 没有邮件通道就无法证明「你是这个邮箱的主人」，
 * 那种情况由登录页直接提示「联系管理员」，不给一个点了必然报错的入口。
 */
@Composable
private fun ResetPasswordDialog(
    apiBase: String,
    turnstileToken: String,
    onDismiss: () -> Unit,
    onDone: () -> Unit,
) {
    var email by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var newPwd by remember { mutableStateOf("") }
    var confirmPwd by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    var tip by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
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
        title = { Text("重置密码") },
        text = {
            Column {
                OutlinedTextField(
                    email, { email = it },
                    label = { Text("账号绑定的邮箱") },
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
                        enabled = email.contains("@") && !busy && cooldown == 0,
                        onClick = {
                            busy = true
                            scope.launch {
                                try {
                                    val ok = apiSendEmailCode(apiBase, email.trim(), turnstileToken)
                                    if (ok) {
                                        tip = "验证码已发送（5 分钟内有效）"
                                        err = null
                                        cooldown = 60
                                    }
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
                    "重置成功后，所有设备上的登录都会失效",
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
                    err = when {
                        !email.contains("@") -> "请填写邮箱"
                        code.isBlank() -> "请填写验证码"
                        newPwd.length < 6 -> "新密码至少 6 位"
                        newPwd != confirmPwd -> "两次输入的新密码不一致"
                        else -> null
                    }
                    if (err != null) return@TextButton
                    busy = true
                    scope.launch {
                        try {
                            val api = im.client.api.Api(apiBase)
                            api.resetPassword(email.trim(), code.trim(), newPwd)
                            onDone()
                        } catch (e: Throwable) {
                            err = e.message ?: e.toString()
                        } finally {
                            busy = false
                        }
                    }
                },
            ) { Text(if (busy) "提交中…" else "重置") }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") } },
    )
}

