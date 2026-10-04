package im.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import im.client.registerTurnstileCallback
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
    var apiBase by remember { mutableStateOf(DEFAULT_API) }
    var wsBase by remember { mutableStateOf(DEFAULT_WS) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var emailCode by remember { mutableStateOf("") }
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
    var oidcToken by remember { mutableStateOf("") }
    // 自动回调失败时才提示手工粘贴；成功路径下这个输入框根本用不上
    var oidcManual by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    /** 拿到 OIDC 令牌后的统一登录动作：自动回调与手工粘贴共用 */
    suspend fun finishOidcLogin(token: String) {
        try {
            val c = ImClient(apiBase.trimEnd('/'), wsBase)
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
    // 人机验证开着、但当前端根本渲染不出来 → 注册必然被服务端 403，提前拦掉并说明原因
    val turnstileBlocked = turnstileOn && turnstileToken.isEmpty() && !turnstileRenderable

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(
            Modifier.fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(44.dp))
            BrandHeader()
            Spacer(Modifier.height(28.dp))
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 2.dp,
                modifier = Modifier.widthIn(max = 440.dp).fillMaxWidth(),
            ) {
                Column(Modifier.padding(horizontal = 28.dp, vertical = 30.dp)) {
                    Text("登录", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "输入账号密码，或使用下方第三方账号",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(22.dp))

                    AuthField(username, { username = it }, "用户名 / 雁书号")
                    Spacer(Modifier.height(14.dp))
                    AuthField(
                        password, { password = it }, "密码",
                        password = !showPassword,
                        trailing = {
                            Text(
                                if (showPassword) "隐藏" else "显示",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable { showPassword = !showPassword }
                                    .padding(horizontal = 8.dp, vertical = 10.dp),
                            )
                        },
                    )

                    // 邮箱常驻（服务端无论是否开验证码都会保存）；验证码相关只在站点开启后出现
                    if (canRegister) {
                        Spacer(Modifier.height(14.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) {
                                AuthField(email, { email = it }, "邮箱（注册用，可留空）")
                            }
                            if (siteCfg?.email_code_enabled == true) {
                                Spacer(Modifier.width(8.dp))
                                OutlinedButton(
                                    enabled = email.contains("@") && !busy,
                                    onClick = {
                                        scope.launch {
                                            try {
                                                val ok = apiSendEmailCode(apiBase.trimEnd('/'), email, turnstileToken)
                                                if (ok) tip = "验证码已发送（5 分钟内有效）"
                                            } catch (e: Throwable) {
                                                error = e.message ?: e.toString()
                                            }
                                        }
                                    },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 14.dp),
                                ) { Text("发送验证码", style = MaterialTheme.typography.labelMedium) }
                            }
                        }
                        if (siteCfg?.email_code_enabled == true) {
                            Spacer(Modifier.height(14.dp))
                            AuthField(emailCode, { emailCode = it }, "邮箱验证码")
                            if (email.isBlank()) {
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "本站要求邮箱验证码：请先填邮箱并点「发送验证码」",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    } else {
                        // 之前这里什么都不显示：注册被关闭时整个注册区（含邮箱框）静默消失，
                        // 用户只会以为「注册功能没了 / 找不到填邮箱的地方」。
                        Spacer(Modifier.height(14.dp))
                        Text(
                            "本站已关闭注册。管理员可在管理后台「认证设置」里勾选「允许注册」后重试。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    // 站点配置没拉到时给个明确提示，否则用户只会看到「这些区域凭空消失」
                    siteCfgErr?.let {
                        Spacer(Modifier.height(12.dp))
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }

                    // Turnstile：小组件由各平台 actual 负责渲染（只有 Web 端真的画得出来）
                    if (turnstileOn) {
                        TurnstileSlot(turnstileToken, turnstileRenderable, turnstileErr)
                    }

                    error?.let {
                        Spacer(Modifier.height(12.dp))
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    tip?.let {
                        Spacer(Modifier.height(12.dp))
                        Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                    }

                    Spacer(Modifier.height(24.dp))
                    Button(
                        enabled = !busy,
                        onClick = {
                            busy = true; error = null; tip = null
                            scope.launch {
                                try {
                                    val client = ImClient(apiBase.trimEnd('/'), wsBase)
                                    client.wireCallbacks()
                                    client.login(username, password)
                                    client.startSession()
                                    saveSession(SavedSession(client.myToken, client.apiBaseUrl, client.gatewayWsUrl))
                                    onLoggedIn(client)
                                } catch (e: Throwable) {
                                    error = e.message ?: e.toString()
                                }
                                busy = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(46.dp),
                        shape = RoundedCornerShape(12.dp),
                    ) { Text(if (busy) "登录中…" else "登录", style = MaterialTheme.typography.titleSmall) }

                    if (canRegister) {
                        Spacer(Modifier.height(10.dp))
                        OutlinedButton(
                            enabled = !busy && !turnstileBlocked,
                            onClick = {
                                busy = true; error = null; tip = null
                                scope.launch {
                                    try {
                                        val client = ImClient(apiBase.trimEnd('/'), wsBase)
                                        client.wireCallbacks()
                                        client.register(username, password, username, "", email, emailCode, turnstileToken)
                                        client.login(username, password)
                                        client.startSession()
                                        saveSession(SavedSession(client.myToken, client.apiBaseUrl, client.gatewayWsUrl))
                                        onLoggedIn(client)
                                    } catch (e: Throwable) {
                                        error = e.message ?: e.toString()
                                    }
                                    busy = false
                                }
                            },
                            modifier = Modifier.fillMaxWidth().height(46.dp),
                            shape = RoundedCornerShape(12.dp),
                        ) { Text("注册并登录", style = MaterialTheme.typography.titleSmall) }
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
                                modifier = Modifier.fillMaxWidth().height(44.dp),
                                shape = RoundedCornerShape(12.dp),
                            ) { Text("使用 ${p.name} 登录") }
                        }
                        if (oidcManual) {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                "自动回跳不可用，请从授权成功页复制令牌粘贴到下方（管理员可在服务端配置 " +
                                    "IM_OIDC_RETURN_ALLOWLIST 打开自动回跳）",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(10.dp))
                            AuthField(oidcToken, { oidcToken = it }, "OIDC Token")
                            Spacer(Modifier.height(12.dp))
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
                                modifier = Modifier.fillMaxWidth().height(44.dp),
                                shape = RoundedCornerShape(12.dp),
                            ) { Text("完成 OIDC 登录") }
                        }
                    }

                    // 服务器地址默认收起，避免和主表单抢视线；改了会自动重新拉站点配置
                    Spacer(Modifier.height(20.dp))
                    Text(
                        if (showConn) "收起服务器地址" else "服务器地址（$apiBase）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .clickable { showConn = !showConn }
                            .padding(horizontal = 6.dp, vertical = 6.dp),
                    )
                    if (showConn) {
                        Spacer(Modifier.height(10.dp))
                        AuthField(apiBase, { apiBase = it }, "API 地址")
                        Spacer(Modifier.height(14.dp))
                        AuthField(wsBase, { wsBase = it }, "WS 地址")
                    }
                }
            }
            Spacer(Modifier.height(36.dp))
        }
    }
}

/** 品牌标识：圆角方块 + 名称 + 一句话说明 */
@Composable
private fun BrandHeader() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(52.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) {
            Text("雁", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.headlineSmall)
        }
        Spacer(Modifier.width(14.dp))
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
        shape = RoundedCornerShape(12.dp),
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
            modifier = Modifier.padding(horizontal = 10.dp),
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
    LaunchedEffect(token) {
        timeout = false
        if (token.isNotEmpty()) return@LaunchedEffect
        delay(10_000)
        if (token.isEmpty()) timeout = true
    }
    Spacer(Modifier.height(16.dp))
    Box(
        Modifier.fillMaxWidth().height(72.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center,
    ) {
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
            modifier = Modifier.padding(horizontal = 14.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}
