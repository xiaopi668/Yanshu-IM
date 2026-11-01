package im.client.call

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 通话音频路由：说话走「通话」通道，默认从听筒出声。
 *
 * 为什么不能省：不设置 `AudioManager.mode`，LiveKit/WebRTC 的声音会走**媒体流**——
 * 从外放喇叭轰出来，既不礼貌（旁边人都听见）也听不清（回声）。通话类 App 必须
 * `MODE_IN_COMMUNICATION` + 关免提（= 听筒）。
 *
 * 三件事：
 * 1. [onJoined]：切通信模式、请求音频焦点（避免和音乐 App 抢）、默认听筒、监听设备断开
 * 2. [setSpeaker]：听筒 ↔ 外放，并把状态写回 [CallMediaExtras.speakerOn]（UI 按钮跟着变）
 * 3. [onLeft]：**全部恢复**——关免提、`MODE_NORMAL`、放弃音频焦点、注销广播
 *
 * 线程：只在主线程改路由（内部 [onMain] 兜底）；`AudioManager` 的很多方法在非主线程改
 * 会有竞态，而 UI 本来就在主线程。
 *
 * 容错：所有 `AudioManager` 调用都 try/catch —— 定制 ROM（部分国产 ROM 的省电/音频策略）
 * 会直接抛异常。路由失败最多是「声音从外放出来」，**不能因此把整通电话判成失败**。
 */
object CallAudioRouter {
    private const val TAG = "CallAudioRouter"

    private val mainHandler = Handler(Looper.getMainLooper())

    /** 当前通话使用的 AudioManager（未接通时为 null） */
    private var audioManager: AudioManager? = null

    /** 通话期间的音频焦点请求；必须持有引用，abandon 时要用同一个 */
    private var focusRequest: AudioFocusRequest? = null

    /** ACTION_AUDIO_BECOMING_NOISY 的接收器（拔耳机/蓝牙断开） */
    private var noisyReceiver: BroadcastReceiver? = null

    /** 是否处于「已接通」的路由状态 */
    private var active: Boolean = false

    /** 焦点变化回调：通话场景不做自动暂停，只记日志（对方声音的优先级由系统保证） */
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        Log.i(TAG, "音频焦点变化: $change")
    }

    /**
     * 接通后调用：进入通信模式、拿音频焦点、默认听筒、监听设备断开。
     * [manager] 只在测试/特殊场景传入，正常从 Context 取。
     */
    fun onJoined(context: Context, manager: AudioManager? = null) = onMain {
        val am = manager ?: runCatching {
            context.applicationContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        }.getOrNull()
        if (am == null) {
            Log.w(TAG, "取不到 AudioManager，跳过音频路由（通话继续，声音走系统默认输出）")
            return@onMain
        }
        audioManager = am
        active = true

        runCatching { am.mode = AudioManager.MODE_IN_COMMUNICATION }
            .onFailure { Log.w(TAG, "切换 MODE_IN_COMMUNICATION 失败", it) }

        requestFocus(am)

        // 语音通话默认走听筒；用户点「免提」才外放
        runCatching { am.isSpeakerphoneOn = false }
            .onFailure { Log.w(TAG, "关闭免提失败（保持系统默认路由）", it) }
        CallMediaExtras.setSpeakerOn(false)

        registerNoisyReceiver(context)
    }

    /**
     * 切换免提。**未接通时直接忽略**（接口约定：UI 在 Connecting 阶段误触不能崩）。
     * 写入 [CallMediaExtras] 的时机是「系统真的改成功了」，所以状态流永远和硬件一致。
     */
    fun setSpeaker(on: Boolean) = onMain {
        val am = audioManager
        if (!active || am == null) {
            Log.i(TAG, "未接通，忽略免提切换($on)")
            return@onMain
        }
        runCatching { am.isSpeakerphoneOn = on }
            .onSuccess {
                Log.i(TAG, "免提=${on}")
                CallMediaExtras.setSpeakerOn(on)
            }
            .onFailure {
                // ROM 抛异常：保持旧状态，UI 不会出现「按钮亮了但声音还在听筒」
                Log.w(TAG, "切换免提失败($on)", it)
            }
    }

    /** 挂断/失败清理时调用：恢复系统音频状态。可重复调用。 */
    fun onLeft() = onMain {
        val am = audioManager
        audioManager = null
        active = false

        runCatching { am?.isSpeakerphoneOn = false }
            .onFailure { Log.w(TAG, "恢复免提状态失败", it) }

        runCatching {
            if (am != null && am.mode != AudioManager.MODE_NORMAL) am.mode = AudioManager.MODE_NORMAL
        }.onFailure { Log.w(TAG, "恢复 MODE_NORMAL 失败", it) }

        val req = focusRequest
        focusRequest = null
        if (am != null && req != null) {
            runCatching { am.abandonAudioFocusRequest(req) }
                .onFailure { Log.w(TAG, "放弃音频焦点失败", it) }
        }

        unregisterNoisyReceiver()
        CallMediaExtras.setSpeakerOn(false)
    }

    /** 请求通话级音频焦点（AUDIOFOCUS_GAIN：让音乐 App 暂停而不是压低） */
    private fun requestFocus(am: AudioManager) {
        runCatching {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setWillPauseWhenDucked(true)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener(focusListener, mainHandler)
                .build()
            focusRequest = req
            val result = am.requestAudioFocus(req)
            if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                // 拿不到焦点也要继续打：通话优先，音量问题交给用户
                Log.w(TAG, "音频焦点未授予($result)，通话继续")
            }
        }.onFailure { Log.w(TAG, "请求音频焦点失败", it) }
    }

    /**
     * 监听「音频输出设备被拔掉」（拔耳机、蓝牙断开）。
     * 不管的话声音会突然从喇叭外放，用户没意识到就变成了公放。
     */
    private fun registerNoisyReceiver(context: Context) {
        if (noisyReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != AudioManager.ACTION_AUDIO_BECOMING_NOISY) return
                Log.i(TAG, "音频输出设备断开（拔耳机/蓝牙），自动关闭免提")
                setSpeaker(false)
            }
        }
        runCatching {
            val filter = IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
            val app = context.applicationContext
            if (Build.VERSION.SDK_INT >= 33) {
                // 系统受保护广播，指定 NOT_EXPORTED 只是收窄暴露面，系统仍会投递
                app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                app.registerReceiver(receiver, filter)
            }
        }.onSuccess {
            noisyReceiver = receiver
        }.onFailure {
            Log.w(TAG, "注册音频设备广播失败（不影响通话）", it)
        }
    }

    private fun unregisterNoisyReceiver() {
        val receiver = noisyReceiver ?: return
        noisyReceiver = null
        val ctx = im.client.db.appContext
        if (ctx == null) {
            Log.w(TAG, "appContext 已丢失，无法注销音频设备广播（进程结束时随进程回收）")
            return
        }
        runCatching { ctx.applicationContext.unregisterReceiver(receiver) }
            .onFailure { Log.w(TAG, "注销音频设备广播失败", it) }
    }

    /** 所有路由改动都收敛到主线程：已经在主线程就同步执行，避免调用方看到「延后生效」 */
    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            runCatching(block).onFailure { Log.w(TAG, "音频路由异常", it) }
        } else {
            mainHandler.post { runCatching(block).onFailure { Log.w(TAG, "音频路由异常", it) } }
        }
    }
}
