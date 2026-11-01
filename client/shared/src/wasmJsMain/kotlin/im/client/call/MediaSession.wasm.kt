package im.client.call

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Web 端媒体面：livekit-client（UMD，随包发在产物根目录，不依赖 CDN）。
 *
 * 为什么用 @JsFun 字符串而不是 @JsModule：不引入 npm 依赖、不改构建脚本，
 * 与仓库里 Turnstile 的做法一致；脚本在首次通话时才注入，不影响首屏。
 */
private const val LIVEKIT_SRC = "livekit-client.umd.min.js"

@JsFun("""(src, onOk, onErr) => {
  if (window.LivekitClient) { onOk(); return; }
  const s = document.createElement('script');
  s.src = src;
  s.async = true;
  s.onload = () => onOk();
  s.onerror = () => onErr('无法加载 livekit-client（检查产物根目录是否包含该文件）');
  document.head.appendChild(s);
}""")
private external fun jsLoadLivekit(src: String, onOk: () -> Unit, onErr: (String) -> Unit)

@JsFun("""(url, token, onOk, onErr) => {
  const LK = window.LivekitClient;
  if (!LK) { onErr('livekit-client 未加载'); return; }
  try {
    const room = new LK.Room({ adaptiveStream: true, dynacast: true });
    window.__imCallRoom = room;
    room.on(LK.RoomEvent.Disconnected, () => { window.__imCallRoom = null; });
    room.connect(url, token)
      .then(() => room.localParticipant.setMicrophoneEnabled(true))
      .then(() => onOk())
      .catch((e) => onErr((e && (e.message || e.name)) ? (e.message || e.name) : String(e)));
  } catch (e) {
    onErr('发起通话失败: ' + (e && e.message ? e.message : String(e)));
  }
}""")
private external fun jsJoinCall(url: String, token: String, onOk: () -> Unit, onErr: (String) -> Unit)

@JsFun("""(muted) => {
  const r = window.__imCallRoom;
  if (!r) return;
  try { r.localParticipant.setMicrophoneEnabled(!muted).catch(() => {}); } catch (e) {}
}""")
private external fun jsSetMuted(muted: Boolean)

@JsFun("""() => {
  const r = window.__imCallRoom;
  window.__imCallRoom = null;
  if (r) { try { r.disconnect(); } catch (e) {} }
}""")
private external fun jsLeaveCall()

private object WebMediaSession : MediaSession {
    override val supported: Boolean = true

    override suspend fun join(url: String, token: String): Result<Unit> {
        if (url.isBlank()) return Result.failure(IllegalStateException("服务端未配置 LiveKit 地址（livekit_url）"))

        // 1) 按需注入 livekit-client
        val loaded = suspendCancellableCoroutine { cont ->
            jsLoadLivekit(LIVEKIT_SRC, { cont.resume(true) }, { msg -> cont.resumeWith(Result.failure(RuntimeException(msg))) })
        }
        if (!loaded) return Result.failure(IllegalStateException("livekit-client 加载失败"))

        // 2) 入会 + 打开麦克风（浏览器会弹权限申请）
        return suspendCancellableCoroutine { cont ->
            jsJoinCall(
                url, token,
                { cont.resume(Result.success(Unit)) },
                { msg -> cont.resume(Result.failure(RuntimeException(msg))) },
            )
        }
    }

    override fun setMuted(muted: Boolean) = jsSetMuted(muted)

    override suspend fun leave() = jsLeaveCall()
}

actual fun createMediaSession(): MediaSession = WebMediaSession
