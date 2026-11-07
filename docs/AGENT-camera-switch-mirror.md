# AGENT — 通话：切换前后摄像头 + 本地镜像预览

> 这份文档是给接手这两个功能的 agent 的作业说明。动手前先读 `AGENT.md` 的 §3（契约）与 §6（坑），
> 本文只讲这两件事的增量。

---

## 0. 一句话目标

通话中能 **在前后摄像头之间切换**，并且**本地预览默认镜像**（前摄）/ 不镜像（后摄），
两端都要真的可用：Android（原生）与 Web（浏览器）。桌面端保持不支持。

## 1. 范围

**做**

- Android：切换前后摄像头、本地预览镜像
- Web：同上
- 两端的「只有一颗摄像头」/「没有摄像头」降级

**不做**

- 桌面端（JVM 侧车只做音频，视频本身就没实现）
- 摄像头分辨率/帧率选择、闪光灯、缩放
- 本地录制/截图

## 2. 现状（代码位置，别重复造）

| 作用 | 路径 |
|---|---|
| 媒体面接口（含视频） | `client/shared/src/commonMain/kotlin/im/client/call/MediaSession.kt` |
| Android 媒体面 | `client/shared/src/androidMain/kotlin/im/client/call/MediaSession.kt` |
| Android 渲染槽位 | `client/shared/src/androidMain/kotlin/im/client/call/AndroidCallVideo.kt` |
| Web 媒体面 + JS 胶水 | `client/shared/src/wasmJsMain/kotlin/im/client/call/MediaSession.wasm.kt` |
| 通话 UI（三端共用） | `client/composeApp/src/commonMain/kotlin/im/app/AppRoot.kt` 的 `InCallOverlay` |
| 视频画面 expect/actual | `client/composeApp/src/*/kotlin/im/app/CallVideoSurface.kt` |
| 附加能力（免提/重连等） | `client/shared/src/commonMain/kotlin/im/client/call/CallMediaExtras.kt` + 三端 actual |

已实现：视频发布/订阅、摄像头开关、`videoSupported`、权限（CAMERA）与前台服务类型。

## 3. API 事实（已核对，不要凭印象写）

### Android（io.livekit:livekit-android 2.29.2）

- 切换：`LocalVideoTrack.switchCamera(deviceId: String = "", position: CameraPosition = ...)`
  - `CameraPosition` 是枚举：`io.livekit.android.room.track.CameraPosition { FRONT, BACK }`
  - `deviceId` 传空串表示「由 SDK 选」，**不要自己拼设备 ID**
- 取当前本地视频轨：`room.localParticipant.getTrackPublication(Track.Source.CAMERA)?.track as? LocalVideoTrack`
  - 注意 `LocalTrackPublication` **没有** `videoTrack` 属性，必须走 `track`
- 镜像：`io.livekit.android.renderer.SurfaceViewRenderer` **没有** `setMirror`（已用 javap 确认）
  - 用 View 级变换：`view.scaleX = -1f`（渲染器是普通 View，可用）
  - 只对**本地预览**那个 renderer 做，远端那个不要动
- `setCameraEnabled` 只有一个 `(Boolean)` 重载，不能带 position —— 别试图用它切换

### Web（随包发的 livekit-client 2.23.0）

- 枚举设备：`await Room.getLocalDevices('videoinput')`
  - **必须先拿到摄像头权限**，否则 `label` 为空、无法区分前后
- 切换：`await room.switchActiveDevice('videoinput', deviceId)`
  - 没有视频轨时调用无效 → 先确保 camera 已开
- 镜像：给本地 `<video>` 元素设 `style.transform = 'scaleX(-1)'`（DOM 层，不在 Kotlin 里）

## 4. 设计（照这个来，理由都是踩过的）

### 4.1 接口扩展（`MediaSession`，全部带默认实现，不破坏三端）

```kotlin
val cameraSwitchSupported: Boolean get() = false
suspend fun switchCamera(): Boolean          // 返回是否真的切了（只有一颗摄像头要能告诉 UI）
fun setLocalMirror(on: Boolean) {}
```

为什么 `switchCamera` 是 suspend 且返回 Boolean：
- 两端都可能重建采集管线（Web 要重新协商），同步接口会卡 UI
- 只有一颗摄像头时「点了没反应」最让人困惑，必须能把结果回给界面

### 4.2 镜像策略（默认值要符合直觉）

- **前摄默认镜像、后摄不镜像**（业界惯例：照镜子看自己）
- 镜像**只作用于本地预览**，绝不镜像发布出去的流（否则对方看到的字是反的）
- 用户手动点过镜像开关后，本次通话内以用户选择为准（不要每次切摄像头又复位，除非切到后摄——那时应强制不镜像）

### 4.3 UI 状态放哪

放 `CallMediaExtras`（与 `speakerOn` / `videoBlocked` 一致，只关心 UI 展示）：

- `cameraSwitchSupported: Boolean`（决定是否显示「翻转」按钮）
- `mirrorOn: StateFlow<Boolean>`（决定按钮文案与是否已应用）

`InCallOverlay` 的按钮行加两个按钮（仅 `videoSupported` 平台显示）：
- 「翻转」：`enabled = media == Connected && cameraSwitchSupported`
- 「镜像」：仅当本地预览可见时显示；点了调 `setLocalMirror(!mirrorOn)`

### 4.4 状态机归属

- 切换/镜像的具体动作由 `MediaSession` 实现，**UI 不直接碰 SDK**
- `CallController` 只做转发（`toggleMirror()` / `switchCamera()`），不新增媒体状态机

## 5. 分步实施（每步都要能编译）

### 步骤 1：接口 + 三端空实现

1. `MediaSession` 加上面三个成员（默认值如上）
2. `CallMediaExtras` 加两个成员（expect + android/desktop/wasm 三个 actual；桌面/wasm 先给常量）
3. 编译三端通过 → 进下一步

### 步骤 2：Android 实现

1. `AndroidCallVideo` 记住本地 renderer 实例（现在只在 `createLocal` 里局部持有）
2. `setLocalMirror(on)`：`localRenderer?.scaleX = if (on) -1f else 1f`
3. `switchCamera()`：
   - 取 `LocalVideoTrack`；为空（摄像头没开）→ 返回 false 并记日志
   - 维护 `currentPosition: CameraPosition`（默认 FRONT），目标取反
   - 调 `track.switchCamera("", target)`；成功后更新 `currentPosition` 与镜像默认值
   - **切换后确认画面还在**：必要时重新 `addRenderer`（旧 renderer 与新 track 的关系需实测；
     若黑屏，就在切换回调里 `removeRenderer` + `addRenderer` 一次）
4. `leave()` 时把镜像状态复位（`scaleX = 1f`），否则下一通电话会继承

### 步骤 3：Web 实现

1. JS 胶水加：`switchCamera(onOk, onErr)` —— `Room.getLocalDevices` 枚举 → 选一个与当前不同的 deviceId → `room.switchActiveDevice`
2. JS 胶水加：`setLocalMirror(on)` —— 给 `#im-local-video video` 设 `transform`
3. Kotlin 侧用 `suspendCancellableCoroutine` 包回调（与现有 `jsJoinCall` 同款）
4. **每段新增 JS 都要过 `node --check`**（这个项目已经踩过「Kotlin 编译通过但 JS 括号错」）

### 步骤 4：UI

1. `InCallOverlay` 按钮行加「翻转」「镜像」
2. 只有一颗摄像头时：「翻转」置灰 + 文案说明（不要静默无效）
3. 本地预览默认镜像：进入 `Connected` 且 camera 打开时按 §4.2 应用一次

## 6. 坑（逐条都对应真实失败模式）

1. **镜像只影响预览**：把 `scaleX` 用在发布管线或对方画面上，对方看到的是反的
2. **Android 没有 setMirror**：别照着网上示例写 `renderer.setMirror(true)`，这个版本不存在
3. **切换摄像头不等于重新开摄像头**：`setCameraEnabled(false/true)` 会重新申请权限、
   可能被系统拒；用 `switchCamera`
4. **Web 枚举设备前必须有权限**：否则 label 全空，切到「同一个设备」看起来像没反应
5. **切换后要复检画面**：Android 上换摄像头后 renderer 可能不再收到帧（黑屏），需要重新挂载
6. **镜像状态要复位**：`leave()` 里忘了清，下一通电话会带着上一通的镜像状态
7. **桌面端要如实 false**：不要为了「按钮能点」假装支持

## 7. 验收清单

**构建**

- [ ] `./gradlew :composeApp:assembleDebug`
- [ ] `./gradlew :composeApp:compileKotlinDesktop :composeApp:compileKotlinWasmJs`
- [ ] `./gradlew :shared:desktopTest --tests 'im.client.call.*'`
- [ ] 新增/修改的 JS 全部 `node --check` 通过

**设备（Android）**

- [ ] 开摄像头后画面正常；点「翻转」能切到另一颗，且不黑屏
- [ ] 前摄默认镜像、后摄不镜像；手动开关能覆盖
- [ ] 只有前摄的设备上，「翻转」置灰并说明原因
- [ ] 挂断再打一通，镜像状态没有残留

**浏览器（Web）**

- [ ] 同一套行为；切换后对方画面跟着变（不是只有本地变）
- [ ] 镜像只影响本地小窗（让对方看屏幕上的字验证）

## 8. 交付报告（发给 lead）

1. 改了哪些文件、每个文件一句话
2. 三条构建命令 + `node --check` 的**真实输出**
3. 设备上验了哪些、没验哪些（没验就直说）
4. 如果发现本文档的 API 描述与实际不符，**以实测为准并写进报告**

> 规矩同 AGENT.md：没跑过验证不要写「已完成」。
