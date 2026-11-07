package im.client.call

import android.content.pm.PackageManager
import android.util.Log
import io.livekit.android.AudioOptions
import io.livekit.android.AudioType
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.RoomOptions
import io.livekit.android.events.DisconnectReason
import io.livekit.android.events.RoomEvent
import io.livekit.android.room.Room
import io.livekit.android.room.track.CameraPosition
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import io.livekit.android.room.track.video.CameraCapturerUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlin.sequences.generateSequence

/**
 * Android 端通话媒体面（LiveKit 官方 SDK `io.livekit:livekit-android`）。**已实现，可用。**
 *
 * 一件事做完整，靠的是三步，缺一步都是「假通话」：
 *
 * 1. **connect** —— `room.connect(wss://…, token)`：入会。token 是服务端一次性签发、
 *    每人一个（identity 不同），所以每次重连都要用新 token 重新 join（CallController 负责）。
 * 2. **开麦** —— `localParticipant.setMicrophoneEnabled(true)` 且返回 true 才算成功。
 *    Web 端第一版只 connect 没开麦，界面显示「通话中」双方却全静音（AGENT.md §6.1），
 *    Android 这里把「返回 false」直接当失败，宁可在界面上报错也不给一个听不见的通话。
 * 3. **释放** —— [leave] 里麦克风、房间、事件订阅、前台服务、音频焦点一个都不能漏，
 *    否则挂断后系统状态栏的麦克风指示灯会一直亮（AGENT.md §6.2：系统资源不会随 UI 销毁）。
 *
 * 踩过的坑（照抄会踩，别问怎么知道的）：
 * - 采集在 Android 12+ 后台会被系统回收 → 入会**之前**先起前台服务（[CallService]），
 *   而且 `startForegroundService` 从后台起会抛异常，只能记日志不能让通话失败。
 * - 不设置 `AudioManager.mode` 的话声音走媒体流从外放轰出来 → [CallAudioRouter] 负责
 *   通信模式 + 听筒 + 音频焦点；路由失败只降级，不判通话失败（定制 ROM 会抛异常）。
 * - `setMicrophoneEnabled(false)` 在连接已断时可能长时间不返回 → 释放时给了 2s 上限，
 *   超时继续 disconnect，不能让「挂断」按钮卡住。
 * - 快速挂断/重连会并发破坏状态 → join/leave 用 [Mutex] 串行；join 进行中收到 leave 时
 *   只置 [leaveRequested]、立即返回，由入会路径自己收尾（避免挂断等 10s 连接超时）。
 *   已知边界：若挂断恰好发生在「CallController 已调 join、但 join 还没拿到锁」的极窄窗口里，
 *   这次 leave 会被 join 入口的复位抹掉，最终由 CallController 在 join 返回后按
 *   `state != InCall` 丢弃结果 —— 那条路径目前不会补第二次 leave()。要彻底封死，
 *   应在 CallController 丢弃结果时补一次 `media.leave()`（3 行，属于它自己的文件）。
 * - 房间被动断开（对端挂断/服务端踢/网络断）不在这里改 CallController 状态，而是调
 *   [CallActions.requestHangUp] 让信令侧统一收尾（AGENT.md §3.2：状态由 CallController 回填）。
 *
 * 事件订阅只处理四类：Connected/Reconnected（结束重连提示）、Reconnecting（开始重连提示 +
 * 20s 兜底计时）、Disconnected/FailedToConnect（请求挂断）。其余事件一律忽略。
 */
private const val TAG = "AndroidMediaSession"

/** 重连超过这个时间没恢复就主动挂断，避免 UI 永远显示「正在重连」 */
private const val RECONNECT_TIMEOUT_MS = 20_000L

/** 关麦的最长等待：连接已断时 SDK 可能迟迟不返回，不能让它卡住挂断 */
private const val MIC_RELEASE_TIMEOUT_MS = 2_000L

/** 挂断最多等 join 多久让出锁；等不到就交给 join 自己收尾 */
private const val LEAVE_LOCK_TIMEOUT_MS = 1_500L

/** 换摄像头后等多久再重挂渲染器：太早新采集器还没起，太晚用户先看到黑屏 */
private const val RENDERER_REATTACH_DELAY_MS = 400L

/** 只有一颗摄像头时给界面的人话（按钮据此置灰并说明原因，不要静默无效） */
private const val SINGLE_CAMERA_HINT = "这台设备只有一颗摄像头，无法翻转"

/** 我们自己产出的、已经是「人话」的失败；[explainFailure] 原样透传，不再二次翻译 */
private class CallFailure(message: String) : RuntimeException(message)

private object AndroidMediaSession : MediaSession {
    /**
     * 内部协程作用域：主线程 + SupervisorJob（单个事件处理失败不能连带取消整个作用域）。
     * 用于 setMuted 的异步开麦/关麦、事件订阅、重连计时。
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** join/leave 串行化，避免快速挂断/重连时并发改 room/事件订阅 */
    private val mutex = Mutex()

    /** 当前房间（仅主线程读写） */
    private var room: Room? = null

    /** 事件订阅 job（仅主线程读写） */
    private var eventsJob: Job? = null

    /** 重连计时 job（仅主线程读写） */
    private var reconnectJob: Job? = null

    /** 正在释放资源：期间到达的事件一律忽略，避免自己 disconnect 触发 requestHangUp 重入 */
    private var tearingDown = false

    /** 有人在这通电话期间调过 leave（用于放弃正在进行的 join） */
    @Volatile
    private var leaveRequested = false

    /** 当前摄像头朝向（仅主线程读写）。默认前摄，与系统相机一致 */
    private var cameraPosition: CameraPosition = CameraPosition.FRONT

    /**
     * 用户在本通电话里手动拨过的镜像选择；null = 还没拨过，按摄像头朝向取默认值。
     *
     * 为什么单独记：文档 §4.2 要求「手动拨过之后以用户为准，不要每次切摄像头又复位」，
     * 只有记下「用户拨过」这件事，才能同时满足这条和「切到后摄强制不镜像」。
     */
    private var mirrorOverride: Boolean? = null

    /**
     * 这台设备此刻能不能通话：只判有没有麦克风。
     *
     * 取不到 appContext 时返回 true —— 初始化顺序上 `CallController` 可能早于
     * `MainActivity.onCreate` 赋值；此时返回 false 会让 UI 直接显示「该平台暂不支持」，
     * 那是误报。真正的失败会在 join 里以可读原因返回。
     */
    override val supported: Boolean
        get() {
            val ctx = im.client.db.appContext ?: return true
            return runCatching {
                ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
            }.getOrDefault(true)
        }

    /** Android 有摄像头与原生渲染器，视频可用 */
    override val videoSupported: Boolean = true

    /**
     * 开关摄像头。只在已接通时生效；失败只影响画面，不动已经接通的声音。
     *
     * 顺序：先要权限再 publish —— 反过来会先弹摄像头再被拒，用户看到两次权限框。
     */
    override fun setVideoEnabled(enabled: Boolean) {
        val r = room ?: return
        scope.launch {
            if (enabled) {
                val ctx = im.client.db.appContext
                if (ctx != null) {
                    when (CallPermissions.requestCamera(ctx)) {
                        MicPermission.Granted -> Unit
                        MicPermission.Denied -> {
                            Log.w(TAG, "摄像头权限被拒")
                            CallMediaExtras.setVideoBlocked(true)
                            return@launch
                        }
                        MicPermission.Blocked -> {
                            Log.w(TAG, "摄像头权限已被拒绝（不再询问）")
                            CallMediaExtras.setVideoBlocked(true)
                            return@launch
                        }
                    }
                    CallMediaExtras.setVideoBlocked(false)
                }
            }
            runCatching { r.localParticipant.setCameraEnabled(enabled) }
                .onSuccess { on ->
                    Log.i(TAG, "摄像头=" + on)
                    if (!on) {
                        AndroidCallVideo.detachLocalTrack()
                        CallMediaExtras.setCameraSwitchHint("")
                    }
                }
                .onFailure { Log.w(TAG, "切换摄像头失败（enabled=" + enabled + "）", it) }
            // publish 成功后把本地画面挂到预览槽位
            if (enabled) {
                runCatching {
                    val pub = r.localParticipant.getTrackPublication(Track.Source.CAMERA)
                    // LocalTrackPublication 没有 videoTrack 便捷属性，要从 track 上转；
                    // 这里要的是 LocalVideoTrack（能 switchCamera），不是父类 VideoTrack
                    val track = pub?.track as? LocalVideoTrack
                    track?.let {
                        // 记住当前朝向，并按 §4.2 落地默认镜像：前摄镜像、后摄不镜像。
                        // 先设镜像再挂渲染器也行 —— AndroidCallVideo 会把状态补到新 View 上。
                        cameraPosition = runCatching { it.options.position }.getOrNull() ?: CameraPosition.FRONT
                        applyMirrorForPosition(cameraPosition)
                        AndroidCallVideo.attachLocalTrack(it)
                    }
                }.onFailure { Log.w(TAG, "本地预览挂载失败", it) }
                // 只有一颗摄像头时把「翻转」置灰的原因说清楚（不能等用户点了才知道）
                refreshCameraSwitchHint()
            }
        }
    }

    /** Android 有 LocalVideoTrack.switchCamera + GL 镜像，能力是真的 */
    override val cameraSwitchSupported: Boolean = true

    /**
     * 本地预览镜像：记下用户的选择并立刻应用（发布出去的流不受影响，否则对方看到的字是反的）。
     */
    override fun setLocalMirror(on: Boolean) {
        mirrorOverride = on
        applyMirror(on)
    }

    /**
     * 切换前后摄像头（文档 §5 步骤 2）。
     *
     * 返回 false 的三种情况都必须让界面能解释：摄像头没开、设备只有一颗摄像头、SDK 切换抛异常。
     * 用 `switchCamera` 而不是 `setCameraEnabled(false/true)`：后者会重新走一遍权限申请，
     * 用户可能被系统再弹一次框、甚至被拒（文档 §6.3）。
     */
    override suspend fun switchCamera(): Boolean = withContext(Dispatchers.Main.immediate) {
        val track = localCameraTrack()
        if (track == null) {
            Log.w(TAG, "切换摄像头：本地视频轨不存在（摄像头没开？）")
            return@withContext false
        }
        val target =
            if (cameraPosition == CameraPosition.FRONT) CameraPosition.BACK else CameraPosition.FRONT
        if (!hasCameraFacing(target)) {
            Log.w(TAG, "切换摄像头：设备上没有 $target 方向的摄像头")
            CallMediaExtras.setCameraSwitchHint(SINGLE_CAMERA_HINT)
            return@withContext false
        }
        val switched = runCatching {
            // deviceId 传空串 = 由 SDK 选。不要自己拼设备 ID：不同 ROM 的编号规则不一样
            track.switchCamera("", target)
        }.onFailure { Log.w(TAG, "切换摄像头异常（target=$target）", it) }.isSuccess
        if (!switched) return@withContext false

        cameraPosition = target
        CallMediaExtras.setCameraSwitchHint("")
        // §4.2：切到后摄强制不镜像（并清掉手动选择）；切回前摄时用户选过就以用户为准
        if (target == CameraPosition.BACK) {
            mirrorOverride = null
            applyMirror(false)
        } else {
            applyMirror(mirrorOverride ?: true)
        }
        // 换摄像头会重开采集器，旧 renderer 可能不再收帧（黑屏、但声音照旧）→ 延迟重挂一次
        scope.launch {
            delay(RENDERER_REATTACH_DELAY_MS)
            AndroidCallVideo.refreshLocalRenderer()
        }
        Log.i(TAG, "已切换摄像头：$target")
        true
    }

    /** 当前本地视频轨（摄像头没开时为 null）。要 LocalVideoTrack —— 只有它才有 switchCamera */
    private fun localCameraTrack(): LocalVideoTrack? = runCatching {
        room?.localParticipant?.getTrackPublication(Track.Source.CAMERA)?.track as? LocalVideoTrack
    }.getOrNull()

    /** 镜像同时写「渲染器」与「界面状态」，避免界面说已镜像而画面没翻（或反过来） */
    private fun applyMirror(on: Boolean) {
        AndroidCallVideo.setLocalMirror(on)
        CallMediaExtras.setMirrorOn(on)
    }

    /** 按摄像头朝向落地默认镜像（§4.2）：前摄镜像、后摄不镜像，用户手动选择优先 */
    private fun applyMirrorForPosition(position: CameraPosition) {
        val effective = if (position == CameraPosition.BACK) false else (mirrorOverride ?: true)
        applyMirror(effective)
    }

    /**
     * 设备上是否存在某个朝向的摄像头。
     *
     * 用 SDK 自己的枚举器判（`CameraCapturerUtils.createCameraEnumerator` 不需要 CAMERA 权限）。
     * 探测失败时返回 true：宁可让用户点一次再看到失败原因，也不要凭一次异常替用户否决功能。
     */
    private fun hasCameraFacing(position: CameraPosition): Boolean {
        val ctx = im.client.db.appContext ?: return true
        return runCatching {
            val enumerator = CameraCapturerUtils.createCameraEnumerator(ctx)
            enumerator.deviceNames.any { name ->
                if (position == CameraPosition.FRONT) enumerator.isFrontFacing(name)
                else enumerator.isBackFacing(name)
            }
        }.getOrDefault(true)
    }

    /**
     * 摄像头打开后刷新「翻转」是否可用：只有一颗摄像头时写人话，
     * 界面据此置灰按钮并显示原因（文档 §5 步骤 4.2：不要静默无效）。
     */
    private fun refreshCameraSwitchHint() {
        val ctx = im.client.db.appContext ?: return
        val count = runCatching {
            CameraCapturerUtils.createCameraEnumerator(ctx).deviceNames.size
        }.getOrDefault(-1)
        // -1 = 探测失败：不要写死「只有一颗」，让用户点了由 switchCamera 回一个真实原因
        CallMediaExtras.setCameraSwitchHint(if (count == 1) SINGLE_CAMERA_HINT else "")
    }

    override suspend fun join(url: String, token: String): Result<Unit> =
        withContext(Dispatchers.Main.immediate) {
            // 新一轮通话：清掉上一轮的「已挂断」标记
            leaveRequested = false
            mutex.withLock { joinLocked(url, token) }
        }

    /**
     * 入会。顺序固定：清残留 → 校验地址 → 权限 → 前台服务 → connect → 开麦 → 路由 → 事件。
     * 任何一步失败都先释放已拿到的资源，再返回可读原因（CallController 会原样显示）。
     */
    private suspend fun joinLocked(url: String, token: String): Result<Unit> {
        // 0) 上一轮可能还挂着（快速重连、重复点击接听）：先彻底释放，保证只有一份媒体资源
        releaseLocked()

        if (url.isBlank()) {
            return Result.failure(CallFailure("服务端未配置通话地址（livekit_url）"))
        }
        val ctx = im.client.db.appContext
            ?: return Result.failure(CallFailure("应用上下文未初始化（见 MainActivity.onCreate）"))

        // 1) 麦克风运行时权限（必须真的拿到，否则后面开麦必然失败）
        when (val perm = CallPermissions.requestMic(ctx)) {
            MicPermission.Granted -> CallMediaExtras.setMicBlocked(false)

            MicPermission.Denied -> {
                CallMediaExtras.setMicBlocked(false)
                val hint = CallPermissions.lastHint
                return Result.failure(
                    CallFailure(
                        if (hint.isBlank()) "麦克风权限被拒绝，无法通话"
                        else "麦克风权限被拒绝，无法通话（$hint）",
                    ),
                )
            }

            MicPermission.Blocked -> {
                CallMediaExtras.setMicBlocked(true)
                return Result.failure(CallFailure("麦克风权限已被拒绝，请在系统设置里开启后重试"))
            }
        }
        if (leaveRequested) return Result.failure(CallFailure("通话已结束"))

        // 2) 前台服务：Android 12+ 没有它，切到后台就会被系统回收麦克风
        CallService.start(ctx)

        var created: Room? = null
        return try {
            // 3) 建房间。CallAudioType：采集/播放走通话通道
            //    （MODE_IN_COMMUNICATION + STREAM_VOICE_CALL + USAGE_VOICE_COMMUNICATION），
            //    不用它的话 WebRTC 会把语音当媒体播放。
            created = LiveKit.create(
                ctx,
                RoomOptions(),
                LiveKitOverrides(audioOptions = AudioOptions(audioOutputType = AudioType.CallAudioType())),
            )
            created.connect(url, token)
            if (leaveRequested) throw CallFailure("通话已结束")

            // 4) 必须真的开麦，返回 false 视为失败（对方听不见的通话没有意义）
            val micOn = try {
                created.localParticipant.setMicrophoneEnabled(true)
            } catch (e: Throwable) {
                Log.w(TAG, "开麦异常（PublishException / 权限 / 被占用）", e)
                throw CallFailure("麦克风打开失败（可能被其他应用占用）")
            }
            if (!micOn) throw CallFailure("麦克风打开失败（可能被其他应用占用）")

            room = created

            // 5) 音频路由：通信模式 + 音频焦点 + 默认听筒（失败只降级，不影响通话）
            CallAudioRouter.onJoined(ctx)

            // 6) 事件订阅：重连提示、被动断开收尾
            subscribe(created)

            // 7) 收尾再确认一次：入会期间用户已经挂断（leave 只置标记、不等锁），
            //    这里必须自己弃掉刚建好的资源，否则会出现「通话界面已回到 Idle，麦克风还开着」。
            if (leaveRequested) throw CallFailure("通话已结束")

            Log.i(TAG, "入会成功：$url")
            Result.success(Unit)
        } catch (t: Throwable) {
            Log.w(TAG, "入会失败：$url", t)
            val message = explainFailure(t, url)
            // 7) 清理：半连接状态的 Room 也要 disconnect，否则会留下开着的麦克风
            releaseLocked(extra = created)
            Result.failure(CallFailure(message))
        }
    }

    /**
     * 静音/取消静音。未接通直接忽略（接口约定，不能崩）；异常只打日志 ——
     * 一次切换失败不该把已经接通的通话打掉。
     */
    override fun setMuted(muted: Boolean) {
        val r = room ?: return
        scope.launch {
            runCatching { r.localParticipant.setMicrophoneEnabled(!muted) }
                .onFailure { Log.w(TAG, "切换静音失败（muted=$muted）", it) }
                .onSuccess { if (!it) Log.w(TAG, "切换静音返回 false（muted=$muted，可能被其他应用占用）") }
        }
    }

    /**
     * 挂断：**幂等**，且必须做全（麦克风 → 房间 → 事件订阅 → 前台服务 → 音频路由）。
     *
     * 两个细节：
     * - 入会还在跑（锁被 join 占着）时不干等，置 [leaveRequested] 后立即返回，
     *   由 join 在 connect/开麦之后自行发现并收尾 —— 否则挂断会被 10s 连接超时拖住。
     * - 释放过程放在 [NonCancellable] 里：CoroutineScope 取消（界面销毁）不能把「关麦」这一步吞掉。
     */
    override suspend fun leave() {
        leaveRequested = true
        withContext(Dispatchers.Main.immediate) {
            if (room == null && eventsJob == null) {
                // 没有接通（或入会进行中）：无资源可释放，join 会自己看到 leaveRequested
                CallMediaExtras.setReconnecting(false)
                Log.i(TAG, "leave：当前没有已建立的媒体资源，直接返回")
                return@withContext
            }
            val released = withTimeoutOrNull(LEAVE_LOCK_TIMEOUT_MS) {
                mutex.withLock { releaseLocked() }
                true
            }
            if (released != true) {
                Log.w(TAG, "join 仍占着锁，挂断交给入会路径收尾（leaveRequested=true）")
            }
        }
    }

    /**
     * 释放全部媒体资源。可重复调用（没有资源时也把外部状态复位）。
     * 调用方必须已持有 [mutex]（或在入会路径内部调用）。
     */
    private suspend fun releaseLocked(extra: Room? = null) {
        withContext(NonCancellable) {
            val target = room ?: extra
            room = null
            tearingDown = true
            try {
                if (target != null) {
                    // 0) 关摄像头：与麦克风同理，不关的话系统状态栏的相机指示器会一直亮
                    runCatching {
                        withTimeoutOrNull(MIC_RELEASE_TIMEOUT_MS) {
                            target.localParticipant.setCameraEnabled(false)
                        }
                    }.onFailure { Log.w(TAG, "关闭摄像头失败（继续清理）", it) }
                    AndroidCallVideo.detachLocalTrack()
                    AndroidCallVideo.detachRemote()
                    // 镜像与翻转状态一起复位：不清的话下一通电话会继承上一通的镜像（文档 §6.6）
                    mirrorOverride = null
                    cameraPosition = CameraPosition.FRONT
                    AndroidCallVideo.setLocalMirror(false)
                    CallMediaExtras.setMirrorOn(false)
                    CallMediaExtras.setCameraSwitchHint("")

                    // 1) 关麦：这是「挂断后系统状态栏麦克风指示灯消失」的关键一步（先关再断）
                    runCatching {
                        withTimeoutOrNull(MIC_RELEASE_TIMEOUT_MS) {
                            target.localParticipant.setMicrophoneEnabled(false)
                        }
                    }.onFailure { Log.w(TAG, "关闭麦克风失败（继续断开连接）", it) }
                    // 2) 断开房间
                    runCatching { target.disconnect() }
                        .onFailure { Log.w(TAG, "断开房间失败（继续清理）", it) }
                }
                // 3) 取消事件订阅与重连计时（之后不会再有回调改 UI 状态）
                eventsJob?.cancel()
                eventsJob = null
                reconnectJob?.cancel()
                reconnectJob = null
                CallMediaExtras.setReconnecting(false)
                // 4) 停前台服务（不在运行也安全）
                im.client.db.appContext?.let { CallService.stop(it) }
                // 5) 清空渲染槽位与 GL 上下文（View 由 Compose 销毁，这里只放引用）
                AndroidCallVideo.clear()
            } finally {
                tearingDown = false
                // 5) 音频路由恢复：关免提、MODE_NORMAL、放弃音频焦点、注销广播
                CallAudioRouter.onLeft()
            }
        }
    }

    /** 订阅房间事件（SharedFlow，订阅随 job 取消而结束） */
    private fun subscribe(r: Room) {
        eventsJob?.cancel()
        eventsJob = scope.launch {
            r.events.events.collect { event -> handleEvent(event) }
        }
    }

    private fun handleEvent(event: RoomEvent) {
        // 自己挂断/失败清理期间的回调不再反推 UI，避免重入 requestHangUp
        if (tearingDown) return
        when (event) {
            is RoomEvent.Connected -> settleReconnect("已接通")
            is RoomEvent.Reconnected -> settleReconnect("重连成功")

            is RoomEvent.TrackSubscribed -> {
                val t = event.track
                if (t is io.livekit.android.room.track.RemoteVideoTrack) {
                    Log.i(TAG, "远端视频轨到达，挂到渲染槽位")
                    AndroidCallVideo.attachRemote(t)
                }
            }

            is RoomEvent.TrackUnsubscribed -> {
                if (event.track is io.livekit.android.room.track.RemoteVideoTrack) {
                    Log.i(TAG, "远端视频轨移除")
                    AndroidCallVideo.detachRemote()
                }
            }

            is RoomEvent.Reconnecting -> {
                Log.i(TAG, "媒体重连中…")
                CallMediaExtras.setReconnecting(true)
                armReconnectWatchdog()
            }

            is RoomEvent.Disconnected -> {
                CallMediaExtras.setReconnecting(false)
                cancelReconnectWatchdog()
                if (event.reason != DisconnectReason.CLIENT_INITIATED) {
                    // 对端挂断/服务端踢/网络断：让信令侧统一收尾（不要在媒体层改 CallController）
                    Log.w(TAG, "房间被动断开（${event.reason}），请求信令侧挂断", event.error)
                    CallActions.requestHangUp()
                } else {
                    Log.i(TAG, "房间已按本机请求断开")
                }
            }

            is RoomEvent.FailedToConnect -> {
                CallMediaExtras.setReconnecting(false)
                cancelReconnectWatchdog()
                Log.w(TAG, "入会失败（事件回调），请求信令侧挂断", event.error)
                CallActions.requestHangUp()
            }

            else -> Unit
        }
    }

    private fun settleReconnect(reason: String) {
        if (CallMediaExtras.reconnecting.value) Log.i(TAG, "重连结束：$reason")
        CallMediaExtras.setReconnecting(false)
        cancelReconnectWatchdog()
    }

    /** 重连兜底计时：20s 没恢复就挂断，不让 UI 卡在「正在重连」 */
    private fun armReconnectWatchdog() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            delay(RECONNECT_TIMEOUT_MS)
            if (CallMediaExtras.reconnecting.value) {
                Log.w(TAG, "重连 ${RECONNECT_TIMEOUT_MS / 1000}s 未恢复，请求挂断")
                CallMediaExtras.setReconnecting(false)
                CallActions.requestHangUp()
            }
        }
    }

    private fun cancelReconnectWatchdog() {
        reconnectJob?.cancel()
        reconnectJob = null
    }

    /**
     * 把连接失败翻译成能排障的人话（这段文本会直接显示在通话界面上，是唯一的线索）。
     *
     * 判定顺序有讲究：先认**具体异常类型**（域名/TLS/拒连/超时），再按关键字认令牌问题。
     * 反过来的话，某些 connect 异常 message 里带 "token" 字样会被误报成「令牌过期」，
     * 用户会去重新发起通话，而真正的问题是网络或地址配错。
     */
    private fun explainFailure(t: Throwable, url: String): String {
        if (t is CallFailure) return t.message ?: "通话连接失败"

        val chain = generateSequence(t) { it.cause }.take(8).toList()

        // 1) 网络层：域名解析 / TLS / 拒绝连接 / 超时 / 不可达
        val network = chain.any {
            it is UnknownHostException || it is SSLException ||
                it is ConnectException || it is SocketTimeoutException ||
                it is NoRouteToHostException
        }
        if (network) {
            return "连不上通话服务器（$url），请检查网络或 livekit_url 配置"
        }

        // 2) 令牌层：401/403、auth/jwt/token 关键字（LiveKit 鉴权失败会带这些）
        val text = chain.joinToString(" | ") { "${it.javaClass.simpleName}: ${it.message.orEmpty()}" }.lowercase()
        if (listOf("401", "403", "unauthor", "forbidden", "token", "jwt", "auth", "expired").any { text.contains(it) }) {
            return "通话令牌无效或已过期（请重新发起通话）"
        }

        // 3) 其余：取链路里最具体的一条 message，最差用类名
        val message = chain.firstNotNullOfOrNull { it.message?.takeIf { m -> m.isNotBlank() } }
        return message ?: t.javaClass.simpleName.ifBlank { "通话连接失败" }
    }
}

actual fun createMediaSession(): MediaSession = AndroidMediaSession
