package im.client.call

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * 麦克风授权结果。**必须三态**：UI 要据此区分「再点一次就行」和「只能去系统设置」。
 *
 * - [Granted]：已授权（或本次弹窗后授权）
 * - [Denied]：本次被拒，下次还能再弹（也包含「宿主没接入，弹不出来」的环境问题）
 * - [Blocked]：之前申请过、且系统已不再显示弹窗（「不再询问」）—— 唯一出路是跳系统设置
 */
enum class MicPermission { Granted, Denied, Blocked }

/**
 * Android 运行时权限桥（麦克风 / 通知）。
 *
 * 为什么用「注入」而不是直接申请：Activity Result API 的 launcher 必须由 Activity 注册，
 * 而 shared 层拿不到 Activity。这跟 [im.client.file.AndroidFilePicker] 是同一套范式，
 * 由 `MainActivity.onCreate` 把三样东西装进来：
 *   - [launch]：拉起权限系统弹窗（麦克风与摄像头共用，回调由 [onResult] 分发）
 *   - [shouldShowRationale]：转发 `Activity.shouldShowRequestPermissionRationale`，
 *     用来判定「不再询问」（没法和「刚被拒」区分时，UI 会给出错误指引）
 *   - [notifications]：Android 13+ 的 POST_NOTIFICATIONS 弹窗（通话通知用）
 *
 * 线程约定：所有字段与挂起等待都在**主线程**读写（[requestMic] 由 MediaSession 在主线程调用）。
 * 未接入宿主（后台环境 / 单测）或 launcher 抛异常（Activity 不在前台）时返回 [MicPermission.Denied]
 * 并写 [lastHint]，绝不挂起不返回 —— 否则通话界面会永远停在「正在接入」。
 */
object CallPermissions {
    private const val TAG = "CallPermissions"

    /** 麦克风权限（指名写死，避免依赖 android.Manifest 常量的可见性差异） */
    const val PERM_RECORD_AUDIO: String = android.Manifest.permission.RECORD_AUDIO

    /** 摄像头权限（视频通话用） */
    const val PERM_CAMERA: String = android.Manifest.permission.CAMERA

    /** Android 13 起的通知权限；低版本没有这个权限，永远视为已授予 */
    const val PERM_POST_NOTIFICATIONS: String = "android.permission.POST_NOTIFICATIONS"

    private const val PREFS_NAME = "im_call"
    private const val KEY_MIC_ASKED = "mic_asked"
    private const val KEY_CAMERA_ASKED = "camera_asked"

    /** 由 MainActivity 注入：拉起权限弹窗（参数是权限名） */
    var launch: ((String) -> Unit)? = null

    /** 由 MainActivity 注入：`Activity.shouldShowRequestPermissionRationale` */
    var shouldShowRationale: ((String) -> Boolean)? = null

    /** 由 MainActivity 注入：拉起 POST_NOTIFICATIONS 弹窗 */
    var notifications: ((String) -> Unit)? = null

    /** 最近一次请求的补充说明（未接入宿主 / Activity 不在前台），供上层拼进可读错误文案 */
    var lastHint: String = ""
        private set

    /** 等待中的麦克风申请（同一时刻只允许一个，重复申请会直接覆盖旧回调） */
    private var pendingMic: ((Boolean) -> Unit)? = null

    /** 等待中的摄像头申请 */
    private var pendingCamera: ((Boolean) -> Unit)? = null

    /** 通知权限申请是否在进行中（结果对通话没有影响，只防重复弹窗） */
    private var pendingNotification: Boolean = false

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 权限是否已授予；不引 androidx.core，直接用 Context.checkSelfPermission（API 23+，minSdk 26 安全） */
    fun isGranted(context: Context, permission: String): Boolean = runCatching {
        context.applicationContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /**
     * 权限 launcher 的通用回调（麦克风 / 摄像头共用一个 launcher）。
     *
     * 分发规则：有摄像头等待就先给它 —— 麦克风在入会时申请、摄像头在接通后申请，
     * 两者不会同时挂起（如果将来要并行申请，这里必须改成按 requestCode 区分）。
     */
    fun onResult(granted: Boolean) {
        pendingCamera?.let { cb ->
            pendingCamera = null
            cb(granted)
            return
        }
        val cb = pendingMic ?: return
        pendingMic = null
        cb(granted)
    }

    /** MainActivity 的 POST_NOTIFICATIONS launcher 回调 */
    fun onNotificationResult(granted: Boolean) {
        pendingNotification = false
        if (!granted) {
            // 通知被拒不影响通话（只是没有「正在通话」通知条），不打扰用户
            Log.i(TAG, "通知权限被拒：通话仍可继续，只是不显示前台服务通知")
        }
    }

    /**
     * 申请（或确认）麦克风权限。
     *
     * 已授权时**不弹窗**——重复调用 join（重连、快速重拨）不会骚扰用户。
     * 判「不再询问」需要两个条件同时成立：系统不再显示说明（rationale=false）+ 之前确实申请过
     * （SharedPreferences 里的 mic_asked）。只看 rationale 会把「首次申请」误判成「永久拒绝」。
     */
    suspend fun requestMic(context: Context): MicPermission {
        lastHint = ""
        val app = context.applicationContext
        if (isGranted(app, PERM_RECORD_AUDIO)) return MicPermission.Granted

        val launcher = launch
        if (launcher == null) {
            lastHint = "当前环境无法弹出授权框"
            Log.w(TAG, "未注入权限 launcher（MainActivity 未初始化？），按拒绝处理")
            return MicPermission.Denied
        }

        val askedBefore = prefs(app).getBoolean(KEY_MIC_ASKED, false)
        // 先落一次「已申请」：系统在「不再询问」后不会给出任何可区分信号，只能靠这里记住
        prefs(app).edit().putBoolean(KEY_MIC_ASKED, true).apply()

        val granted = awaitMicPermission(launcher) ?: return MicPermission.Denied
        if (granted) return MicPermission.Granted

        val rationaleVisible = shouldShowRationale?.invoke(PERM_RECORD_AUDIO)
        val blocked = askedBefore && rationaleVisible == false
        return if (blocked) MicPermission.Blocked else MicPermission.Denied
    }

    /**
     * 挂起等待系统弹窗结果；返回 null 表示**没弹出来**（launcher 抛异常，典型是 Activity 不在前台）。
     * 这种情况返回 [MicPermission.Denied] 比无限等待安全：通话界面会立刻显示失败原因。
     */
    private suspend fun awaitMicPermission(launcher: (String) -> Unit): Boolean? =
        suspendCancellableCoroutine { cont ->
            pendingMic = { granted -> if (cont.isActive) cont.resume(granted) }
            cont.invokeOnCancellation { pendingMic = null }
            val launched = runCatching { launcher(PERM_RECORD_AUDIO) }
            if (launched.isFailure) {
                pendingMic = null
                lastHint = "系统授权框没能弹出（界面不在前台）"
                Log.w(TAG, "拉起权限弹窗失败", launched.exceptionOrNull())
                if (cont.isActive) cont.resume(null)
            }
        }


    /**
     * 申请摄像头权限。语义与 [requestMic] 完全一致（三态 + 不重复弹窗 + 不永久挂起）。
     *
     * 单独一个方法而不是把权限名做成参数：两者「不再询问」的判定要各记一个 asked 标记，
     * 混在一起会把「只拒过麦克风」误判成「摄像头也永久拒绝了」。
     */
    suspend fun requestCamera(context: Context): MicPermission {
        lastHint = ""
        val app = context.applicationContext
        if (isGranted(app, PERM_CAMERA)) return MicPermission.Granted

        val launcher = launch
        if (launcher == null) {
            lastHint = "当前环境无法弹出授权框"
            Log.w(TAG, "未注入权限 launcher（MainActivity 未初始化？），按拒绝处理")
            return MicPermission.Denied
        }

        val askedBefore = prefs(app).getBoolean(KEY_CAMERA_ASKED, false)
        prefs(app).edit().putBoolean(KEY_CAMERA_ASKED, true).apply()

        // 显式给类型参数：不写的话 cont.resume(null) 会让推断成 Nothing?
        val granted = suspendCancellableCoroutine<Boolean?> { cont ->
            pendingCamera = { ok -> if (cont.isActive) cont.resume(ok) }
            cont.invokeOnCancellation { pendingCamera = null }
            val launched = runCatching { launcher(PERM_CAMERA) }
            if (launched.isFailure) {
                pendingCamera = null
                lastHint = "系统授权框没能弹出（界面不在前台）"
                Log.w(TAG, "拉起摄像头授权弹窗失败", launched.exceptionOrNull())
                if (cont.isActive) cont.resume(null)
            }
        } ?: return MicPermission.Denied
        if (granted) return MicPermission.Granted

        val rationaleVisible = shouldShowRationale?.invoke(PERM_CAMERA)
        val blocked = askedBefore && rationaleVisible == false
        return if (blocked) MicPermission.Blocked else MicPermission.Denied
    }

    /**
     * 申请通知权限（Android 13+）。由 [CallService.start] 在起前台服务前调用。
     * 成败都不影响通话，所以这里不挂起、不返回结果，异常也只打日志。
     */
    fun requestNotifications() {
        if (Build.VERSION.SDK_INT < 33) return
        val ctx = im.client.db.appContext ?: return
        if (isGranted(ctx, PERM_POST_NOTIFICATIONS)) return
        val launcher = notifications ?: return
        if (pendingNotification) return
        pendingNotification = true
        runCatching { launcher(PERM_POST_NOTIFICATIONS) }.onFailure {
            pendingNotification = false
            Log.w(TAG, "拉起通知权限弹窗失败（不影响通话）", it)
        }
    }
}
