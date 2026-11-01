# yanshu-call-agent — 桌面端通话侧车

桌面（JVM）端没有官方的 Kotlin LiveKit SDK。本目录用 **Rust 官方 SDK** 实现媒体层，
以**独立进程**的形式被 JVM 客户端调用。

```text
┌──────────────────────────┐        stdin/stdout (JSON Lines)        ┌───────────────────────┐
│ Compose Desktop (JVM)    │ ──────────────────────────────────────▶ │ yanshu-call-agent     │
│  DesktopMediaSession     │ ◀────────────────────────────────────── │  (Rust, livekit 0.10) │
└──────────────────────────┘          ready / state / error          └───────────────────────┘
                                                                               │
                                                                        WebRTC（LiveKit SFU）
```

## 为什么是独立进程

| 方案 | 体积 | 工期 | 维护 | 结论 |
|---|---|---|---|---|
| **Rust SDK 侧车（本目录）** | ~10MB/平台 | 1–2 周 | 中 | 选用 |
| JCEF 内嵌 Chromium | +120MB | 1–2 天 | 低 | 体积不可接受 |
| webrtc-java 手搓 LiveKit 信令 | ~15MB | 2–4 周 | 高（等于自研 SDK） | 不做 |
| 引导用户去浏览器 | 0 | 2 小时 | 无 | 体验割裂，仅兜底 |

独立进程的额外好处：媒体栈崩了不会带走 IM 客户端；升级 SDK 只需换一个可执行文件。

## 协议

stdout **只**输出协议，日志一律走 stderr（JVM 按行解析，杂输出会污染）。

**JVM → 侧车**

| 命令 | 说明 |
|---|---|
| `{"cmd":"join","url":"wss://…","token":"…","mic":true}` | 入会并开麦（默认开） |
| `{"cmd":"set_muted","muted":true}` | 静音/取消静音 |
| `{"cmd":"leave"}` | 离会并退出进程 |
| `{"cmd":"ping"}` | 存活探测，回 `pong` |
| `{"cmd":"selftest"}` | 不需要网络的冒烟：走一遍状态机 |

**侧车 → JVM**

| 事件 | 说明 |
|---|---|
| `{"evt":"ready","version":"0.1.0"}` | 进程就绪 |
| `{"evt":"state","state":"connecting|connected|reconnecting|disconnected"}` | 媒体状态 |
| `{"evt":"error","message":"…","fatal":true}` | `fatal=true` 表示进程将退出，JVM 上报失败而不是重试 |
| `{"evt":"pong"}` | ping 应答 |
| `{"evt":"selftest_ok"}` | 自检通过 |

约定：退出码 0 = 正常，1 = 致命错误；一个进程只服务一次通话。

## 构建

### 默认（stub 后端，不需要 libwebrtc）

```bash
cd desktop-call
cargo build --release
cargo test          # 6 项：协议解析、事件序列化、stub 状态机
```

stub 不连网、不开麦，只走状态机 —— 用来开发和验证 **协议 + JVM 侧**。
CI 的 `rust-sidecar` job 就跑这一档，因此不需要 cmake / libwebrtc。

### 真实媒体（`livekit` feature）

前置：`cmake`、`clang`、`pkg-config`、`libasound2-dev`（Linux）。
libwebrtc 由 `webrtc-sys` 在首次构建时下载/编译，**耗时且体积大**。

```bash
cargo build --release --features livekit
```

> 已验证：Mac/Linux/Windows 的预编译包由上游提供；首次构建请留足时间与磁盘。

## JVM 侧如何使用

`client/shared/src/desktopMain/kotlin/im/client/call/`：

- `Sidecar.kt`：定位二进制、启进程、按行读协议、stderr 转印
- `MediaSession.kt`：实现 `MediaSession`（join/setMuted/leave）与 `setOnBroken`

**二进制查找顺序**（`Sidecar.locate()`）：

1. `-Dim.call.sidecar=/abs/path`（开发调试）
2. `<app dir>/native/yanshu-call-agent` 或 `<app dir>/native/<os>-<arch>/…`（jpackage 安装形态）
3. `PATH`（系统包管理安装）

找不到时 `supported=false`，界面提示「该平台暂不支持通话」，不会假装在通话。

本地联调：

```bash
cargo build --release --manifest-path desktop-call/Cargo.toml
./gradlew :composeApp:run -Dim.call.sidecar=$PWD/desktop-call/target/release/yanshu-call-agent
```

## 打包

jpackage / Compose Desktop 的安装包里，把二进制放到应用目录的 `native/` 下：

```
app/
├── yanshu-im.jar …
└── native/
    └── yanshu-call-agent        # 可执行权限
```

跨平台分发时按 `native/<os>-<arch>/yanshu-call-agent` 组织（`Sidecar.locate()` 已支持）。

交叉编译建议：

```bash
# Linux x64
cargo build --release --target x86_64-unknown-linux-gnu --features livekit
# Windows（在 Linux 上需要 mingw 工具链）
cargo build --release --target x86_64-pc-windows-gnu --features livekit
```

## 测试

| 层级 | 命令 | 覆盖 |
|---|---|---|
| Rust 单测 | `cargo test` | 协议解析/序列化、stub 状态机 |
| 协议冒烟 | `printf '{"cmd":"selftest"}\n' \| ./target/debug/yanshu-call-agent` | 真实进程、stdout 只有协议 |
| JVM 集成 | `./gradlew :shared:desktopTest --tests 'im.client.call.*'` | 启进程、解析事件、join→connected→leave（无二进制时自动跳过） |

## 已知边界（诚实记录）

- **默认构建不含真实媒体**：不带 `--features livekit` 时是 stub，能跑协议但没有声音。
- **真机音频未验证**：本仓库的开发环境没有完整 libwebrtc 构建条件，`livekit` feature
  的代码依据 0.10.0 源码 API 编写（`Room::connect` / `PlatformAudio` /
  `publish_track` / `mute`），但**尚未在真机上跑过一次通话**。
- **音频路由**：桌面端由系统默认设备决定。要选设备需要扩展协议（`list_devices` /
  `set_device`），`PlatformAudio` 已提供枚举接口。
- **重连**：SDK 自己会重连并抛 `Reconnecting`/`Reconnected`，侧车已转成状态事件；
  JVM 侧目前只把异常显示在界面，不会自动重拨。
- **桌面端视频**：未实现（当前只做语音）。
