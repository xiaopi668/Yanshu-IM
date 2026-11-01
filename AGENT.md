# AGENT.md — 移动端通话（Android 语音/视频）

> 这份文档是给**接手移动端通话实现的 agent**看的：它只讲这一件事，包含现状、契约、
> 实施步骤、验收方式与本项目已经踩过的坑。动手前请把「2. 现状」和「3. 契约」读完，
> 那里的每一条都是从真实 bug 里换来的，不要绕开。

---

## 0. 一句话目标

让 Android 端的 1v1 通话**真的有声音**：App 内接听后能入会、能说话、能听到对方、
挂断后麦克风立刻释放。Web 端已经跑通，Android 目前是"假 InCall"。

---

## 1. 范围

**做**

- Android 端语音通话（1v1），含权限、音频路由、前台服务、界面状态
- 与现有信令/UI 的对接（不重写信令）

**不做（本次）**

- 桌面端（JVM）媒体面：没有官方 Kotlin SDK，维持 `supported = false`
- 多人通话 / 屏幕共享 / 录制
- 服务端改动（token 签发已经就绪，见 §2）

视频（摄像头）列为**可选阶段 6**，语音跑通并验收后再做。

---

## 2. 现状（已核对代码，不是设想）

### 2.1 已经就绪的部分

| 环节 | 位置 | 状态 |
|---|---|---|
| 呼叫信令（invite/accept/reject/hangup/timeout） | `server/internal/call/call.go` + `client/shared/.../CallController.kt` | ✅ 可用 |
| LiveKit JWT 签发（主被叫各一个，identity 不同） | `CallManager.issue()` | ✅ 可用 |
| `livekit_url` 下发 | `GET /v1/site-config` 的 `livekit_url` | ✅ 可用（部署需配 `IM_LIVEKIT_HOST`） |
| 通话状态机 + 媒体状态 | `CallController`（`CallState` / `CallMediaState` / `CallUiState`） | ✅ 可用 |
| Web 端媒体面 | `client/shared/src/wasmJsMain/.../MediaSession.wasm.kt` | ✅ 已跑通，**可作参考实现** |
| 通话 UI（接听/呼叫/通话中） | `client/composeApp/src/commonMain/.../AppRoot.kt` | ✅ 可用（含静音/挂断按钮） |

### 2.2 缺的部分（就是你要做的）

```
client/shared/src/androidMain/kotlin/im/client/call/MediaSession.kt
```

当前内容是 `UnsupportedMediaSession`：`supported = false`，三个方法都是空实现。
**你的核心工作就是把它换成真正的 LiveKit 实现。**

---

## 3. 契约（必须遵守，否则 UI 会显示错、麦克风会漏关）

### 3.1 `MediaSession` 接口

```kotlin
// client/shared/src/commonMain/kotlin/im/client/call/MediaSession.kt
interface MediaSession {
    val supported: Boolean
    suspend fun join(url: String, token: String): Result<Unit>
    fun setMuted(muted: Boolean)
    suspend fun leave()
}
expect fun createMediaSession(): MediaSession
```

**逐条语义**

1. `supported` 必须反映"这台设备此刻能不能通话"（有麦克风、SDK 可用）。返回 `true`
   之后 UI 就不再提示"暂不支持"，所以**不要在实现完成前返回 true**。
2. `join` **必须真的把麦克风打开**再返回成功。只连上房间不 `setMicrophoneEnabled(true)`
   等于"接通了但对方听不见"——Web 端第一版就是这个毛病。
3. `join` 失败要返回**可读原因**（权限被拒 / 连不上 / token 过期 / 地址不可达）。
   `CallController` 会把它直接显示在通话界面上，这是唯一的排障线索。
4. `leave()` 必须幂等，且**一定要释放麦克风**（`setMicrophoneEnabled(false)` +
   `room.disconnect()` + 释放音频焦点）。忘了这一步，通知栏的麦克风指示器会一直亮着。
5. `setMuted` 在未接通时被调用要安全（直接忽略，不要崩）。

### 3.2 `CallController` 如何调用你

- 接听/被叫接听后：`scope.launch { media.join(livekitUrl, token) }`，
  结果回填到 `CallUiState.media`（`Connecting → Connected / Failed`）
- 静音按钮：`toggleMute()` → `media.setMuted(next)`
- 所有结束路径（挂断/拒接/取消/对端挂断/超时/忙）：`teardownMedia()` → `media.leave()`

**不要**在 `MediaSession` 里改 `CallController` 的状态；状态由 `CallController` 回填。

### 3.3 令牌与地址

- `url` 来自 `/v1/site-config` 的 `livekit_url`，部署侧会配成 `wss://...`
- `token` 是**一次性**的 room join token，**每人一个**，identity 不同；
  同一个人重连要重新申请（`CallController` 会带着服务端签发的新 token 再调 `join`）
- 令牌不能复用给另一个 room

---

## 4. 前置条件（缺一个就做不完）

1. **LiveKit 服务可达**：`wss://<host>`，443 或自定义端口，证书有效。
   本仓库 `deploy/docker-compose.yml` + `deploy/livekit.yaml`（`port 7880`、
   `rtc.tcp_port 7881`、`rtc.port_range 50000-50200`、`turn.udp_port 3478`）。
   **手机在移动网络下必须能走 TURN**，否则只能在同一局域网内通。
2. **logic 服务配了 `IM_LIVEKIT_HOST`**：否则 `livekit_url` 是默认的
   `ws://127.0.0.1:7880`，手机连不上。
3. **两台设备/一个真机 + 一个浏览器**：双端互拨才能真正验证"能听见"。
4. 构建可跑：`./gradlew :composeApp:assembleDebug` 能出包（见 §8 的本地构建注记）。

---

## 5. 实施步骤（按阶段提交，每阶段都能独立验收）

### 阶段 1：依赖、权限、最小可通话

**改哪里**

- `client/shared/build.gradle.kts` → `androidMain` 加 `io.livekit:livekit-android`
- `client/composeApp/src/androidMain/AndroidManifest.xml` →
  `<uses-permission android:name="android.permission.RECORD_AUDIO" />`；
  如做视频再加 `CAMERA`；前台服务若在阶段 3 加，此时先不写
- `client/shared/src/androidMain/kotlin/im/client/call/MediaSession.kt` → 真实现

**实现骨架**

```kotlin
package im.client.call

import android.content.Context
import io.livekit.android.LiveKit
import io.livekit.android.RoomOptions
import io.livekit.android.room.Room
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

private object AndroidMediaSession : MediaSession {
    override val supported: Boolean = true

    private var room: Room? = null

    override suspend fun join(url: String, token: String): Result<Unit> = runCatching {
        if (url.isBlank()) error("服务端未配置通话地址（livekit_url）")
        // appContext 由 MainActivity.onCreate 赋值，定义在 shared/androidMain 的 db/DbFactory.kt
        val ctx = im.client.db.appContext
            ?: error("应用上下文未初始化（见 MainActivity.onCreate）")
        // 1) 连房间
        val r = LiveKit.create(ctx, RoomOptions())
        r.connect(url, token)
        // 2) 必须真的开麦，否则"接通但对方听不见"
        r.localParticipant.setMicrophoneEnabled(true)
        room = r
    }

    override fun setMuted(muted: Boolean) {
        room?.localParticipant?.setMicrophoneEnabled(!muted)
    }

    override suspend fun leave() {
        val r = room ?: return
        room = null
        runCatching { r.localParticipant.setMicrophoneEnabled(false) }
        runCatching { r.disconnect() }
    }
}

actual fun createMediaSession(): MediaSession = AndroidMediaSession
```

> 上面的 API 名称以 `io.livekit:livekit-android` 当前版本为准，可能需要按实际版本调整；
> 关键是三件事：**connect(url, token) → 开麦 → 断线/挂断时关麦并 disconnect**。

**权限申请**：`RECORD_AUDIO` 是运行时权限，必须**在用户点击"接听"的手势里**发起
（和 Web 端 `input.click()` 必须留在手势调用栈是同一类问题）。
推荐在 `MainActivity` 注册 `ActivityResultContracts.RequestPermission`，
把结果通过一个共享的挂起函数暴露给 `MediaSession`（参考 `AndroidFilePicker` 的注入方式：

```kotlin
// MainActivity.onCreate
CallPermissions.request = { perm -> permissionLauncher.launch(perm) }
```

**验收**

- 两台设备互拨：接听后双方都能听到（不静音）
- 点"挂断"后，系统状态栏的麦克风指示器消失
- 拒绝权限：界面显示"麦克风权限被拒绝"，而不是一直转圈

---

### 阶段 2：音频路由（听筒 / 扬声器 / 蓝牙）

**为什么必须做**：不设置 `AudioManager` 的话，声音会走媒体流（喇叭外放），
通话场景下既不礼貌也听不清。

**要点**

- `audioManager.mode = AudioManager.MODE_IN_COMMUNICATION`
- 语音默认走听筒：`isSpeakerphoneOn = false`
- 提供"免提"开关：`isSpeakerphoneOn = true`
- 通话结束恢复：`mode = MODE_NORMAL`、`isSpeakerphoneOn = false`、`abandonAudioFocus`
- 请求音频焦点（`AudioFocusRequest`，Android 8+），避免和音乐播放器抢
- 蓝牙：监听 `AudioManager.ACTION_AUDIO_BECOMING_NOISY` / `SCO` 状态（可选，阶段 2 末尾）

**验收**

- 接听后默认从听筒出声
- 点"免提"切到外放，再点切回
- 通话中播放音乐 App：音乐被压低/暂停，挂断后恢复

---

### 阶段 3：前台服务（通话不能被杀）

**为什么必须做**：Android 12+ 后台麦克风会被系统回收；用户切到别的 App 再回来，
通话可能已经断了。

**要点**

- `<service android:name=".CallService"
   android:foregroundServiceType="microphone"
   android:exported="false" />`
- 通话开始 → `startForeground(通知)`；结束 → `stopForeground(STOP_FOREGROUND_REMOVE)`
- 通知带"挂断"按钮（`PendingIntent` → `CallController.hangUp()`）
- Android 13+ 需要 `POST_NOTIFICATIONS` 运行时权限
- Android 14+ 前台服务类型要声明并在启动时校验权限，否则抛 `SecurityException`

**验收**

- 通话中按 Home，30 秒后回来，通话仍在
- 通知栏有"正在通话"，点挂断能结束
- 锁屏状态下能挂断

---

### 阶段 4：界面

现有 `InCallOverlay` 已经显示媒体状态（Connecting / Connected / Failed / Unsupported）
和静音/挂断按钮，**这里只需要补**：

- 通话时长（从 `CallUiState` 的 Connected 时刻起算）
- 免提开关（阶段 2 的 `isSpeakerphoneOn`）
- 失败态给出可操作提示（例如"权限被拒，去设置里开启"）

> 注意：通话 UI 在 `commonMain`，三端共用。加 Android 专有按钮时用
> `expect/actual` 提供一个 `@Composable fun PlatformCallActions(...)`，
> 不要在 common 里写 `if (isAndroid)`（KMP 没有这种开关）。

---

### 阶段 5：异常路径

| 场景 | 期望行为 |
|---|---|
| 权限被拒 | 界面明确提示 + 提供"去设置"入口，不进入 Connected |
| 入会失败（token 过期/地址不可达） | 显示 `mediaError` 原文，可重试 |
| 通话中网络切换（WiFi→4G） | LiveKit 自动重连；UI 显示"正在重连"，超过 N 秒则挂断并提示 |
| 对端挂断 | 已由信令处理（`Hangup` → `teardownMedia()`），确认麦克风关闭 |
| 应用被杀 | 前台服务保活；极端情况重进 App 时 `CallState` 应为 Idle（不要停在 InCall） |

---

### 阶段 6（可选）：视频

- 摄像头：`localParticipant.setCameraEnabled(true)` + `CAMERA` 权限
- **渲染方式**：LiveKit 提供 `SurfaceViewRenderer`（`io.livekit.android.renderer`），
  Compose 里用 `AndroidView` 承载
- **千万不要**把 `SurfaceViewRenderer` 包进会裁剪的容器（`Card` / `clip(...)`）：
  SurfaceView 与 Compose 的图层是分开的，被裁剪会黑屏。结构：

```kotlin
Box(Modifier.size(160.dp, 120.dp).clip(MaterialTheme.shapes.medium)) {
    // 圆角只作用在背景层；视频视图本身不施加 clip
    Surface(Modifier.matchParentSize(), color = MaterialTheme.colorScheme.surfaceVariant) {}
    AndroidView(factory = { ctx -> SurfaceViewRenderer(ctx) }, modifier = Modifier.fillMaxSize())
}
```

- 视频区域不要加 `Surface` 阴影（会掉帧）

---

## 6. 本项目踩过的坑（直接决定成败，别重犯）

1. **"能接通"不等于"有声音"**
   Web 端第一版只 `connect()` 没开麦，双方静音；UI 却显示"通话中"。
   现在 `CallMediaState` 把"信令通了"和"媒体通了"分开，别把它合回去。

2. **DOM/系统资源不会随 Compose 销毁**
   人机验证小组件（DOM 节点）在登录页卸载后仍留在页面上，因为隐藏逻辑写在
   已销毁的 `LaunchedEffect` 里。同理：**麦克风、音频焦点、前台服务必须在
   `leave()` 里显式释放**，不能依赖"界面没了自然就停了"。

3. **用户手势必须在同一调用栈**
   Web 端 `input.click()` 被 `scope.launch` 挪到下一个调度周期，浏览器判定非手势、
   直接忽略 → "点📎没反应"。Android 的权限申请同理：在点击事件里同步发起。

4. **地址必须是 wss:// 且外部可达**
   HTTPS 站点加载 `ws://` 会被混合内容拦截；`127.0.0.1` / 容器内主机名手机连不上。
   部署检查：`curl https://<域名>/v1/site-config` 里的 `livekit_url`。

5. **TURN 不是可选项**
   手机经常在运营商 NAT 后，纯 SFU 直连会失败。`livekit.yaml` 的 `turn` 段必须
   有公网可达的 `udp/3478`（或 TURN/TLS 的 443）。

6. **游标类 bug 要用测试钉住**
   本项目的数据同步曾因"本地游标被服务端 last_seq 顶替"两次回归
   （见 `MemoryStoreTest.setConversationsMustNotAdvanceMaxSeq`）。
   同理：媒体状态的迁移（Idle→Connecting→Connected→Idle）值得写一条状态机测试。

---

## 7. 验收清单（交付前逐条打勾）

**功能**

- [ ] A 呼叫 B，B 接听，双方能听到对方（真实两台设备，非同一台模拟器）
- [ ] 静音后对方听不到，取消静音恢复
- [ ] 挂断后麦克风指示器消失（系统状态栏）
- [ ] 拒接 / 对方取消 / 超时 三条路径都能回到 Idle，且不残留媒体资源
- [ ] 通话中切换 WiFi↔移动网络，能自动重连
- [ ] 后台（Home 键）30 秒后回来仍在通话（阶段 3）
- [ ] 权限被拒时给出可操作提示，不卡在 Connecting

**代码**

- [ ] `./gradlew :composeApp:compileKotlinDesktop` 通过（common 没被破坏）
- [ ] `./gradlew :composeApp:compileKotlinWasmJs` 通过
- [ ] `./gradlew :composeApp:assembleDebug` 出包
- [ ] `./gradlew :shared:desktopTest` 通过
- [ ] 没有把 Android 专有 API 泄进 `commonMain`

---

## 8. 工程约定

- **提交粒度**：每个阶段一次提交，message 说明"改了什么 / 为什么 / 怎么验证的"
- **不要动**：`CallController` 的状态机语义、信令协议、`MediaSession` 接口签名
  （要改先在 PR 里说明理由）
- **不要引入**除 `io.livekit:livekit-android` 之外的 UI 依赖；M3 组件已够用
- **构建注记**：本仓库用 AGP 9.4.1 + Compose 1.12，配了兼容开关
  `android.builtInKotlin=false` / `android.newDsl=false`（见 `client/composeApp/build.gradle.kts`
  顶部注释）。加依赖时不要改这些开关，也不要顺手升级 AGP/Gradle。
- **CI**：6 个 job（server / proto / client / android / docker / e2e）必须全绿；
  `android` job 会真的打 APK，加依赖后要确认它没超时。
- **验证原则**：没在真机上听到声音，就不要写"已修复"。本项目已经因为
  "只编译不验证"写出过两次无效修复（游标语义、文件选择器）。

---

## 9. 文件索引

| 作用 | 路径 |
|---|---|
| 媒体面接口 + expect | `client/shared/src/commonMain/kotlin/im/client/call/MediaSession.kt` |
| **要改的 Android 实现** | `client/shared/src/androidMain/kotlin/im/client/call/MediaSession.kt` |
| 参考实现（Web，已跑通） | `client/shared/src/wasmJsMain/kotlin/im/client/call/MediaSession.wasm.kt` |
| 信令状态机 + 媒体状态 | `client/shared/src/commonMain/kotlin/im/client/CallController.kt` |
| 通话界面 | `client/composeApp/src/commonMain/kotlin/im/app/AppRoot.kt`（`InCallOverlay` 等） |
| Android 宿主 / 权限注入点 | `client/composeApp/src/androidMain/kotlin/im/app/MainActivity.kt` |
| 文件选择器的注入范例 | `client/shared/src/androidMain/kotlin/im/client/file/*` |
| Android 应用上下文（appContext） | `client/shared/src/androidMain/kotlin/im/client/db/DbFactory.kt` |
| 服务端 token 签发 | `server/internal/call/call.go`、`server/cmd/gateway/main.go` |
| site-config（livekit_url） | `server/cmd/logic/auth_flow.go` 的 `publicSiteConfig` |
| 部署 | `deploy/docker-compose.yml`、`deploy/livekit.yaml`、`deploy/.env.example` |

**桌面端（已实现，语音）**：

| 作用 | 路径 |
|---|---|
| 侧车（Rust + livekit 0.10） | `desktop-call/`（协议、stub/真实后端、构建说明见其 README） |
| JVM 侧进程封装 | `client/shared/src/desktopMain/kotlin/im/client/call/Sidecar.kt` |
| 桌面 `MediaSession` 实现 | `client/shared/src/desktopMain/kotlin/im/client/call/MediaSession.kt` |
| 媒体异常上报 | `MediaSession.setOnBroken(...)`（`CallController` 会置为 Failed） |

外部参考：LiveKit Android SDK 文档（`io.livekit:livekit-android`）——请以当前版本为准，
本仓库不锁定其版本，接入时在 `shared/build.gradle.kts` 的 `androidMain` 中声明。

---

## 10. 完成定义（Definition of Done）

1. 两台真机（或真机 + 浏览器）能互相通话并听清
2. 挂断后无残留：麦克风指示器消失、通知消失、回到 Idle
3. 三个编译目标 + 单测 + CI 全绿
4. `MediaSession.kt` 的注释如实描述实现状态（不要再留下"暂未实现"）
5. 本文件的 §7 验收清单全部打勾，并把验证方式写进 commit message
