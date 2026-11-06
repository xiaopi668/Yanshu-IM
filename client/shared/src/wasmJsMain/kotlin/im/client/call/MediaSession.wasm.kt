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
    // 远程视频轨到了就挂到 DOM 视频层（Compose 画不了视频）
    room.on(LK.RoomEvent.TrackSubscribed, (track) => {
      if (track && track.kind === 'video') window.__imVideo.attachRemote(track);
    });
    room.on(LK.RoomEvent.TrackUnsubscribed, (track) => {
      if (track && track.kind === 'video') window.__imVideo.detachRemote();
    });
    room.on(LK.RoomEvent.Disconnected, () => { window.__imCallRoom = null; window.__imVideo.hide(); });
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

// ---------- 视频层 ----------
// 浏览器里视频必须由 DOM <video> 渲染，Compose 的 canvas 画不了。
// 这里用「两个 fixed 图层 + 由 Compose 回传位置」的方式摆放（与人机验证小组件同款做法）。

@JsFun("""() => {
  if (window.__imVideo) return true;
  const mk = (id, muted) => {
    const d = document.createElement('div');
    d.id = id;
    d.style.cssText = 'position:fixed;display:none;background:#000;overflow:hidden;z-index:4;border-radius:16px;';
    const v = document.createElement('video');
    v.autoplay = true;
    v.playsInline = true;
    // 本地预览必须静音：否则自己的声音从扬声器再进麦克风，直接啸叫
    v.muted = !!muted;
    v.style.cssText = 'width:100%;height:100%;object-fit:cover;';
    d.appendChild(v);
    document.body.appendChild(d);
    return d;
  };
  const remote = mk('im-remote-video', false);
  const local = mk('im-local-video', true);
  local.style.zIndex = '6';
  local.style.borderRadius = '12px';
  local.style.boxShadow = '0 2px 8px rgba(0,0,0,0.35)';
  window.__imVideo = {
    place: (left, top, w, h, localSize) => {
      remote.style.left = left + 'px';
      remote.style.top = top + 'px';
      remote.style.width = w + 'px';
      remote.style.height = h + 'px';
      remote.style.display = 'block';
      // 本地小窗放在右下角，留 12px 边距
      const s = Math.max(64, localSize);
      local.style.left = (left + w - s - 12) + 'px';
      local.style.top = (top + h - s - 12) + 'px';
      local.style.width = s + 'px';
      local.style.height = s + 'px';
      local.style.display = 'block';
    },
    attachRemote: (track) => {
      const v = remote.querySelector('video');
      try { track.attach(v); } catch (e) {}
      remote.style.display = 'block';
    },
    detachRemote: () => {
      const v = remote.querySelector('video');
      try { v.srcObject = null; } catch (e) {}
      remote.style.display = 'none';
    },
    attachLocal: (track) => {
      const v = local.querySelector('video');
      try { track.attach(v); } catch (e) {}
      local.style.display = 'block';
    },
    detachLocal: () => {
      const v = local.querySelector('video');
      try { v.srcObject = null; } catch (e) {}
      local.style.display = 'none';
    },
    hide: () => { remote.style.display = 'none'; local.style.display = 'none'; },
  };
  return true;
}""")
private external fun jsInstallVideo(): Boolean

@JsFun("""(left, top, w, h, localSize) => {
  if (!window.__imVideo) return;
  window.__imVideo.place(left, top, w, h, localSize);
}""")
private external fun jsPlaceVideo(left: Int, top: Int, w: Int, h: Int, localSize: Int)

@JsFun("""(enabled) => {
  const r = window.__imCallRoom;
  if (!r) return;
  try {
    r.localParticipant.setCameraEnabled(!!enabled).then((pub) => {
      if (pub && pub.videoTrack) window.__imVideo.attachLocal(pub.videoTrack);
      if (!enabled) window.__imVideo.detachLocal();
    }).catch(() => {});
  } catch (e) {}
}""")
private external fun jsSetVideo(enabled: Boolean)

@JsFun("""() => {
  if (window.__imVideo) window.__imVideo.hide();
}""")
private external fun jsHideVideo()

private object WebMediaSession : MediaSession {
    override val supported: Boolean = true

    // 视频：浏览器有 getUserMedia，天然支持；摄像头权限由 setCameraEnabled 触发
    override val videoSupported: Boolean = true

    override fun setVideoEnabled(enabled: Boolean) {
        runCatching {
            jsInstallVideo()
            jsSetVideo(enabled)
            if (!enabled) jsHideVideo()
        }
    }

    override fun placeVideoSurfaces(left: Int, top: Int, width: Int, height: Int, localSize: Int) {
        runCatching {
            jsInstallVideo()
            jsPlaceVideo(left, top, width, height, localSize)
        }
    }

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

    override suspend fun leave() {
        runCatching { jsHideVideo() }
        jsLeaveCall()
    }
}

actual fun createMediaSession(): MediaSession = WebMediaSession
