package im.client.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log

/**
 * 通话前台服务：让 Android 12+ 不会在后台把麦克风采集回收掉。
 *
 * 背景（AGENT.md §5 阶段 3）：通话中按 Home 键后，没有前台服务的进程会进 cached 状态，
 * 系统会掐掉麦克风/网络，表现为「切出去 30 秒回来通话已经断了」。所以入会前起前台服务，
 * 挂断时停掉。
 *
 * 两个入口：
 * - [ACTION_START]：由 [MediaSession] 在入会前调用 → `startForeground(通知)`
 * - [ACTION_HANGUP]：通知栏的「挂断」按钮 → [CallActions.requestHangUp]（Service 拿不到
 *   `CallController`，也不该持有 Activity，见 CallActions 的说明）+ `stopSelf()`
 *
 * 权限（AndroidManifest 已声明）：
 * `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MICROPHONE`（Android 14 起按类型校验）。
 * `POST_NOTIFICATIONS` 只在 13+ 需要，拿不到也能通话，只是没有通知条。
 *
 * 容错：从后台启动前台服务在 Android 12+ 会抛 `ForegroundServiceStartNotAllowedException`，
 * 定制 ROM 还可能抛 `SecurityException` —— 一律只打日志，**不能因为通知起不来就把通话判失败**。
 */
class CallService : Service() {

    companion object {
        private const val TAG = "CallService"
        private const val CHANNEL_ID = "im_call"
        private const val CHANNEL_NAME = "通话"
        private const val NOTIFICATION_ID = 0x1C411 // 一个固定 id：同一时刻只有一通电话
        private const val ACTION_START = "im.client.call.action.START"
        private const val ACTION_HANGUP = "im.client.call.action.HANGUP"

        /** 起前台服务；调用方在主线程（MediaSession.join） */
        fun start(context: Context) {
            val ctx = context.applicationContext
            // Android 13+：没有通知权限就不显示通知条，但前台服务照起（通话优先）
            CallPermissions.requestNotifications()
            runCatching {
                val intent = Intent(ctx, CallService::class.java).setAction(ACTION_START)
                // API 26+ 可直接用 startForegroundService（minSdk 26）
                ctx.startForegroundService(intent)
            }.onFailure {
                // ForegroundServiceStartNotAllowedException(API31+) / SecurityException / IllegalStateException
                Log.w(TAG, "前台服务启动失败（通话继续，但后台可能被系统回收）", it)
            }
        }

        /** 停前台服务；不在运行也安全 */
        fun stop(context: Context) {
            runCatching {
                context.applicationContext.stopService(Intent(context.applicationContext, CallService::class.java))
            }.onFailure { Log.w(TAG, "停止前台服务失败", it) }
        }

        /** 通知里展示的对方标识；空则用通用文案 */
        private fun peerLabel(): String = CallActions.peerLabel.ifBlank { "语音通话" }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HANGUP -> {
                // 通知栏挂断：交给界面层登记的动作统一收尾（关麦、断房间、回 Idle）
                val handled = CallActions.requestHangUp()
                Log.i(TAG, "通知栏挂断：requestHangUp handled=$handled")
                stopForegroundCompat()
                stopSelf()
                return START_NOT_STICKY
            }

            else -> {
                // ACTION_START，或系统重建时 intent 为空：两种都必须立刻 startForeground，
                // 否则 5 秒后系统会抛「did not then call Service.startForeground()」
                if (!startForegroundCompat()) {
                    // 起不来就别留个空壳服务（否则用户会看到一个没有通话的通知）
                    stopSelf()
                }
                return START_NOT_STICKY
            }
        }
    }

    override fun onDestroy() {
        stopForegroundCompat()
        super.onDestroy()
    }

    /**
     * 带类型地进入前台（API 29+ 必须指明类型，Android 14 起会校验）。
     *
     * 类型要**同时**包含 microphone 与 camera：调用方只传了 microphone 的话，
     * 一旦开了摄像头，系统会认为「你在用未声明的类型采集」而抛 SecurityException ——
     * 而且是在打开摄像头的瞬间崩，不在启动服务时，很难联想到是这里的类型没写全。
     */
    private fun startForegroundCompat(): Boolean {
        val notification = buildNotification()
        val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        return runCatching {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(NOTIFICATION_ID, notification, types)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        }.getOrElse {
            Log.w(TAG, "startForeground 失败（没有通知权限/类型未声明/后台启动被限制）", it)
            false
        }
    }

    private fun stopForegroundCompat() {
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
            .onFailure { Log.w(TAG, "stopForeground 失败", it) }
    }

    /** 通知渠道：IMPORTANCE_LOW —— 常驻但不响铃、不弹横幅（通话中不能再被自己的通知吵到） */
    private fun ensureChannel() {
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (nm.getNotificationChannel(CHANNEL_ID) != null) return
            val channel = NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW).apply {
                description = "通话进行中的常驻通知，可直接挂断"
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
            nm.createNotificationChannel(channel)
        }.onFailure { Log.w(TAG, "创建通知渠道失败（通话不受影响）", it) }
    }

    private fun buildNotification(): Notification {
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("正在通话")
            .setContentText(peerLabel())
            // 系统内置图标：不引新资源（APK 里没有语音图标，新增资源会牵动 compose 资源生成）
            .setSmallIcon(android.R.drawable.stat_sys_phone_call)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(Notification.CATEGORY_CALL)
            .addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_menu_close_clear_cancel,
                    "挂断",
                    hangUpPendingIntent(),
                ).build(),
            )

        // 点通知回通话界面（拿不到启动 Intent 就退化成不可点）
        val launch = runCatching { packageManager.getLaunchIntentForPackage(packageName) }.getOrNull()
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val contentPi = runCatching {
                PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE)
            }.getOrNull()
            if (contentPi != null) builder.setContentIntent(contentPi)
        }
        return builder.build()
    }

    private fun hangUpPendingIntent(): PendingIntent =
        PendingIntent.getService(
            this,
            1,
            Intent(this, CallService::class.java).setAction(ACTION_HANGUP),
            PendingIntent.FLAG_IMMUTABLE,
        )
}
