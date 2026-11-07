package im.client.call

import android.util.Log
import io.livekit.android.renderer.SurfaceViewRenderer
import io.livekit.android.room.track.RemoteVideoTrack
import io.livekit.android.room.track.VideoTrack
import livekit.org.webrtc.EglBase

/**
 * Android 视频渲染槽位。
 *
 * 为什么要单独一个 object：视频画面是原生 View（[SurfaceViewRenderer]），而通话状态机在
 * shared 层、Compose 在 composeApp 层。两边都需要它 —— Compose 负责创建/销毁 View，
 * 媒体层负责把轨道挂上去。用「两个具名槽位 + 双向补挂」把两者解耦：
 *
 * - View 先到、轨道后到 → 轨道到达时发现槽位已有 renderer，立即 addRenderer
 * - 轨道先到、View 后到 → bind 时发现槽位已有轨道，立即 addRenderer
 *
 * 少任何一种情况都会出现「画面一直黑着、音频正常」——这是最容易漏的时序问题。
 *
 * 线程约定：全部在主线程（Compose 的 factory/onRelease、LiveKit 的事件回调都在主线程）。
 */
object AndroidCallVideo {
    private const val TAG = "AndroidCallVideo"

    private var remoteRenderer: SurfaceViewRenderer? = null
    private var localRenderer: SurfaceViewRenderer? = null

    private var remoteTrack: RemoteVideoTrack? = null
    private var localTrack: VideoTrack? = null

    /**
     * 共享的 EGL 上下文：SurfaceViewRenderer 必须先 init 才能出画面。
     * 只创建一次、最后一个 renderer 解绑时释放 —— 每通电话都新建会泄漏 GL 上下文。
     */
    private var eglBase: EglBase? = null

    /**
     * 本地预览是否镜像。
     *
     * 存成字段而不是「读 renderer 当前值」：Compose 会重建 View（切前后台、重进通话页），
     * 新 renderer 是干净状态，必须由这里把它补回用户选的样子，否则镜像会「自己关掉」。
     */
    private var localMirrored = false

    private fun egl(): EglBase {
        eglBase?.let { return it }
        val created = EglBase.create()
        eglBase = created
        return created
    }

    /** 新建 renderer 后必须调一次；失败只记日志，不抛给上层 */
    fun prepare(renderer: SurfaceViewRenderer) {
        runCatching {
            // sharedContext=null 时 WebRTC 会自建；这里显式给一个共享上下文，避免多实例抢资源
            renderer.init(egl()!!.eglBaseContext, null, IntArray(0), null)
        }.onFailure { Log.w(TAG, "renderer.init 失败（画面可能黑屏）", it) }
    }

    /**
     * 创建远端画面视图并登记。
     *
     * 返回 [android.view.View] 而不是 SurfaceViewRenderer：composeApp 不依赖
     * livekit-android（shared 里是 implementation，不传递），UI 层也不该认识媒体 SDK 的类型。
     */
    fun createRemote(context: android.content.Context): android.view.View {
        val renderer = SurfaceViewRenderer(context)
        remoteRenderer = renderer
        prepare(renderer)
        remoteTrack?.let { attach(it, renderer) }
        return renderer
    }

    fun createLocal(context: android.content.Context): android.view.View {
        val renderer = SurfaceViewRenderer(context)
        localRenderer = renderer
        prepare(renderer)
        // 新 View 是干净状态：把用户选过的镜像补回去（否则重进通话页镜像就丢了）
        applyMirror(renderer)
        localTrack?.let { attachLocal(it, renderer) }
        return renderer
    }

    /** 视图离开组合：先解绑轨道，再释放 EGL 资源 */
    fun destroyRemote(view: android.view.View?) {
        remoteRenderer?.let { r ->
            remoteTrack?.let { runCatching { it.removeRenderer(r) } }
        }
        (view as? SurfaceViewRenderer)?.let { runCatching { it.release() } }
        remoteRenderer = null
        maybeReleaseEgl()
    }

    fun destroyLocal(view: android.view.View?) {
        localRenderer?.let { r ->
            localTrack?.let { runCatching { it.removeRenderer(r) } }
        }
        (view as? SurfaceViewRenderer)?.let { runCatching { it.release() } }
        localRenderer = null
        maybeReleaseEgl()
    }

    /** 远端视频轨到达/切换 */
    internal fun attachRemote(track: RemoteVideoTrack) {
        remoteTrack?.let { old -> remoteRenderer?.let { runCatching { old.removeRenderer(it) } } }
        remoteTrack = track
        remoteRenderer?.let { attach(track, it) }
    }

    internal fun detachRemote() {
        remoteTrack?.let { t -> remoteRenderer?.let { runCatching { t.removeRenderer(it) } } }
        remoteTrack = null
    }

    internal fun attachLocalTrack(track: VideoTrack) {
        localTrack?.let { old -> localRenderer?.let { runCatching { old.removeRenderer(it) } } }
        localTrack = track
        localRenderer?.let { attachLocal(track, it) }
    }

    internal fun detachLocalTrack() {
        localTrack?.let { t -> localRenderer?.let { runCatching { t.removeRenderer(it) } } }
        localTrack = null
    }

    /**
     * 本地预览镜像开关（只影响本地预览小窗）。
     *
     * 用 `SurfaceViewRenderer.setMirror`（继承自 `livekit.org.webrtc.SurfaceViewRenderer`，
     * 最终落到 `EglRenderer.mirrorHorizontally`，是 GL 层的镜像，画面内容真的会翻）。
     * 不用 `view.scaleX = -1f`：SurfaceView 的画面由独立图层合成，View 变换不一定作用于
     * surface 内容 —— docs/AGENT-camera-switch-mirror.md 里「没有 setMirror」的描述与
     * livekit-android 2.29.2 的实际情况不符，以这里为准。
     */
    fun setLocalMirror(on: Boolean) {
        localMirrored = on
        localRenderer?.let { applyMirror(it) }
    }

    /**
     * 切换摄像头后重新挂载本地轨道。
     *
     * 为什么需要：换摄像头会重开采集器，旧的 renderer 可能不再收到帧（表现就是黑屏，
     * 音频照旧）—— 文档 §6.5。remove + add 一次成本很低，调用方延迟一点调即可。
     */
    fun refreshLocalRenderer() {
        val renderer = localRenderer ?: return
        val track = localTrack ?: return
        runCatching { track.removeRenderer(renderer) }
            .onFailure { Log.w(TAG, "切换后解绑本地预览失败（继续重挂）", it) }
        attachLocal(track, renderer)
        // 重挂不应该改变镜像状态，补一次以防 SDK 内部重绘路径重置了它
        applyMirror(renderer)
    }

    private fun applyMirror(renderer: SurfaceViewRenderer) {
        runCatching { renderer.setMirror(localMirrored) }
            .onFailure { Log.w(TAG, "设置镜像失败（预览不翻转）", it) }
    }

    /** 挂断时清空全部槽位（View 由 Compose 销毁，这里只管轨道引用与 GL 上下文） */
    internal fun clear() {
        remoteTrack = null
        localTrack = null
        remoteRenderer = null
        localRenderer = null
        // 镜像状态一起复位：不清理的话下一通电话会继承上一通（文档 §6.6）
        localMirrored = false
        maybeReleaseEgl()
    }

    private fun attach(track: RemoteVideoTrack, renderer: SurfaceViewRenderer) {
        runCatching { track.addRenderer(renderer) }
            .onFailure { Log.w(TAG, "远端视频挂载失败", it) }
    }

    private fun attachLocal(track: VideoTrack, renderer: SurfaceViewRenderer) {
        runCatching { track.addRenderer(renderer) }
            .onFailure { Log.w(TAG, "本地预览挂载失败", it) }
    }

    private fun maybeReleaseEgl() {
        if (remoteRenderer != null || localRenderer != null) return
        runCatching { eglBase?.release() }
        eglBase = null
    }
}
