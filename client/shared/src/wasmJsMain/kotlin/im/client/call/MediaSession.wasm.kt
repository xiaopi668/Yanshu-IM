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

// ---------- 摄像头翻转 / 本地镜像 ----------
// 两个动作都在 DOM/JS 层：设备枚举、重新协商属于 livekit-client，
// 镜像只是本地 <video> 的一个 CSS 变换（Compose 的 canvas 碰不到它）。

/** 只有一颗摄像头时给界面的人话（按钮据此置灰并说明原因，不要静默无效） */
private const val SINGLE_CAMERA_HINT = "这台设备只有一颗摄像头，无法翻转"

@JsFun("""(onOk, onErr) => {
  const LK = window.LivekitClient;
  const r = window.__imCallRoom;
  if (!LK || !r) { onErr('通话未接通'); return; }
  const pubs = r.localParticipant && r.localParticipant.videoTrackPublications;
  // 没有视频轨时 switchActiveDevice 是无效的（文档 §3）：先让用户开摄像头
  if (!pubs || pubs.size === 0) { onErr('摄像头还没打开，先开摄像头再翻转'); return; }
  const usable = (devs) => (devs || []).filter((d) => d && d.deviceId && d.deviceId !== 'default');
  const currentId = () => {
    try {
      const active = r.getActiveDevice('videoinput');
      if (active) return active;
    } catch (e) {}
    try {
      const pub = Array.from(pubs.values()).find((p) => p && p.videoTrack);
      const track = pub && pub.videoTrack && pub.videoTrack.mediaStreamTrack;
      const st = track && track.getSettings && track.getSettings();
      return (st && st.deviceId) || '';
    } catch (e) {}
    return '';
  };
  // 枚举设备前必须有摄像头权限，否则 label 全空、分不清前后摄（文档 §6.4）。
  // 这里已经开过摄像头，所以第二个参数直接要求权限即可（不会再弹框）。
  LK.Room.getLocalDevices('videoinput', true).then((devs) => {
    const list = usable(devs);
    // 少于两个设备：不是错误，是「这台机器只有一颗摄像头」——回 false 让界面解释
    if (list.length < 2) { onOk(false); return; }
    const active = currentId();
    const next = list.find((d) => d.deviceId !== active);
    if (!next) { onOk(false); return; }
    return r.switchActiveDevice('videoinput', next.deviceId, true).then((sw) => {
      const ok = (typeof sw === 'boolean') ? sw : !!(sw && (sw.isSwitched === undefined ? true : sw.isSwitched));
      if (ok) onOk(true); else onErr('浏览器拒绝了摄像头切换');
    });
  }).catch((e) => onErr((e && e.message) ? e.message : String(e)));
}""")
private external fun jsSwitchCamera(onOk: (Boolean) -> Unit, onErr: (String) -> Unit)

/**
 * 探测当前摄像头朝向，并告诉 Kotlin 两件事：是不是后摄、是不是只有一颗摄像头。
 *
 * 摄像头刚打开时 publication 可能还没到，所以自己在 JS 里重试几次（200ms × 10），
 * 比在 Kotlin 里写轮询简单，也不会阻塞主线程。
 */
@JsFun("""(onOk) => {
  const LK = window.LivekitClient;
  const r = window.__imCallRoom;
  if (!LK || !r) { onOk(false, false); return; }
  let tries = 0;
  const attempt = () => {
    tries += 1;
    const pubs = r.localParticipant && r.localParticipant.videoTrackPublications;
    if (!pubs || pubs.size === 0) {
      if (tries < 10) { setTimeout(attempt, 200); return; }
      onOk(false, false);
      return;
    }
    LK.Room.getLocalDevices('videoinput', true).then((devs) => {
      const list = (devs || []).filter((d) => d && d.deviceId && d.deviceId !== 'default');
      let id = '';
      try { id = r.getActiveDevice('videoinput') || ''; } catch (e) {}
      if (!id) {
        try {
          const pub = Array.from(pubs.values()).find((p) => p && p.videoTrack);
          const track = pub && pub.videoTrack && pub.videoTrack.mediaStreamTrack;
          const st = track && track.getSettings && track.getSettings();
          id = (st && st.deviceId) || '';
        } catch (e) {}
      }
      const cur = list.find((d) => d.deviceId === id);
      const label = (cur && cur.label) || '';
      // 浏览器不提供结构化的「前置/后置」，只能看 label（中文 ROM 的 Chrome 常给「后置摄像头」）。
      // 认不出来就按前摄处理 —— 照镜子的默认值，与 Android/系统相机一致。
      const isBack = /(back|rear|environment|后置|後置|背面)/i.test(label);
      onOk(isBack, list.length < 2);
    }).catch(() => onOk(false, false));
  };
  attempt();
}""")
private external fun jsProbeCameraMirror(onOk: (Boolean, Boolean) -> Unit)

/** 镜像只改本地预览那个 <video>，发布出去的流不受影响（文档 §6.1） */
@JsFun("""(on) => {
  const box = document.getElementById('im-local-video');
  const v = box && box.querySelector('video');
  if (v) v.style.transform = on ? 'scaleX(-1)' : '';
}""")
private external fun jsSetLocalMirror(on: Boolean)

private object WebMediaSession : MediaSession {
    override val supported: Boolean = true

    // 视频：浏览器有 getUserMedia，天然支持；摄像头权限由 setCameraEnabled 触发
    override val videoSupported: Boolean = true

    override fun setVideoEnabled(enabled: Boolean) {
        runCatching {
            jsInstallVideo()
            jsSetVideo(enabled)
            if (enabled) {
                // 摄像头刚开：按前后摄落地默认镜像 + 刷新「翻转」是否可用（§4.2 / §5 步骤 4.2）。
                // jsSetVideo 是异步的，所以探测在 JS 侧自己重试，直到 publication 出现。
                applyDefaultMirror()
            } else {
                jsHideVideo()
                CallMediaExtras.setCameraSwitchHint("")
            }
        }
    }

    /** 浏览器有 getUserMedia + 设备枚举，前后摄切换是真的 */
    override val cameraSwitchSupported: Boolean = true

    /** 用户在本通电话里手动拨过的镜像选择；null = 还没拨过，按摄像头朝向取默认 */
    private var mirrorOverride: Boolean? = null

    /**
     * 探测序号：JS 侧的探测会重试最多 2 秒，挂断/重开摄像头期间回来的旧回包必须丢弃，
     * 否则它会把上一通电话的镜像状态写回界面（按钮文案与画面就对不上了）。
     */
    private var mirrorEpoch = 0

    override fun setLocalMirror(on: Boolean) {
        mirrorOverride = on
        applyMirror(on)
    }

    /**
     * 切换前后摄像头。
     *
     * 返回值语义与 Android 一致：false = 没切成。其中「只有一颗摄像头」是**可解释的失败**，
     * 要把原因写给界面（按钮置灰）；其它失败（未接通/浏览器拒绝）只回 false，不写死原因。
     */
    override suspend fun switchCamera(): Boolean {
        var singleCamera = false
        val ok = suspendCancellableCoroutine { cont ->
            jsSwitchCamera(
                { switched ->
                    if (!switched) singleCamera = true
                    if (cont.isActive) cont.resume(switched)
                },
                { msg ->
                    // 打日志而不是写 hint：这类失败是暂时的，写进 hint 会把按钮永久置灰
                    println("切换摄像头失败: $msg")
                    if (cont.isActive) cont.resume(false)
                },
            )
        }
        if (ok) {
            CallMediaExtras.setCameraSwitchHint("")
            applyDefaultMirror()
        } else if (singleCamera) {
            CallMediaExtras.setCameraSwitchHint(SINGLE_CAMERA_HINT)
        }
        return ok
    }

    /**
     * 按当前摄像头朝向落地默认镜像（§4.2）：后摄不镜像、前摄镜像；
     * 用户在本通电话里手动拨过，则以用户为准（切到后摄仍然强制不镜像）。
     *
     * 为什么能直接覆盖用户选择：只有在「切到后摄」时我们才清掉 mirrorOverride，
     * 切回前摄时用户之前的选择仍然生效 —— 这就是文档要的「不要每次切摄像头又复位」。
     */
    private fun applyDefaultMirror() {
        val epoch = ++mirrorEpoch
        runCatching {
            jsProbeCameraMirror { isBack, singleCamera ->
                if (epoch != mirrorEpoch) return@jsProbeCameraMirror // 过期回包：这通电话已经结束了
                CallMediaExtras.setCameraSwitchHint(if (singleCamera) SINGLE_CAMERA_HINT else "")
                if (isBack) {
                    mirrorOverride = null
                    applyMirror(false)
                } else {
                    applyMirror(mirrorOverride ?: true)
                }
            }
        }.onFailure { println("探测摄像头朝向失败: $it") }
    }

    /** 镜像同时改 DOM 与界面状态，避免界面说「已镜像」而画面没翻 */
    private fun applyMirror(on: Boolean) {
        runCatching { jsSetLocalMirror(on) }
        CallMediaExtras.setMirrorOn(on)
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
        // 镜像与翻转状态复位：不清的话下一通电话会继承上一通（文档 §6.6）
        mirrorEpoch += 1 // 让还在重试中的探测回包失效
        mirrorOverride = null
        runCatching { jsSetLocalMirror(false) }
        CallMediaExtras.setMirrorOn(false)
        CallMediaExtras.setCameraSwitchHint("")
    }
}

actual fun createMediaSession(): MediaSession = WebMediaSession
