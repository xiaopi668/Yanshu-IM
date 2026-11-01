//! 媒体后端。
//!
//! 为什么用 enum 而不是 trait 对象：trait 里的 `async fn` 不是 dyn-compatible，
//! 而这里只有两个实现（stub / livekit），enum 分发最简单，也不需要引入 async-trait。
//!
//! 更重要的原因：LiveKit 的 Rust SDK 依赖 cmake + libwebrtc，编译重、环境要求高。
//! 把「真正连房间」收进一个 enum 分支后，协议层 / 状态机 / JVM 侧都能在没有这些
//! 工具链的机器上开发和测试（默认走 stub）。

pub mod stub;

#[cfg(feature = "livekit")]
pub mod livekit;

use std::fmt;

/// 桥接后的状态，直接映射成协议里的 `state` 字段
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MediaState {
    Connecting,
    Connected,
    #[allow(dead_code)] // 由 livekit 后端在 RoomEvent::Reconnecting 时上报，stub 不产生
    Reconnecting,
    Disconnected,
}

impl MediaState {
    #[allow(dead_code)] // 供后续把状态字符串统一收口时使用
    pub fn as_str(self) -> &'static str {
        match self {
            MediaState::Connecting => "connecting",
            MediaState::Connected => "connected",
            MediaState::Reconnecting => "reconnecting",
            MediaState::Disconnected => "disconnected",
        }
    }
}

#[derive(Debug)]
pub struct MediaError(pub String);

impl fmt::Display for MediaError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}

impl std::error::Error for MediaError {}

pub type MediaResult<T> = Result<T, MediaError>;

/// 一次通话的媒体后端
pub enum Backend {
    Stub(stub::StubBackend),
    #[cfg(feature = "livekit")]
    Live(livekit::LiveKitBackend),
}

impl Backend {
    /// 按编译特性选择后端。默认（无 livekit feature）= stub。
    /// `sink` 供真实后端把运行期状态（重连/断开）直接写回协议。
    pub fn new(emitter: crate::emitter::Emitter) -> Self {
        #[cfg(feature = "livekit")]
        {
            Backend::Live(livekit::LiveKitBackend::new(emitter))
        }
        #[cfg(not(feature = "livekit"))]
        {
            let _ = emitter;
            Backend::Stub(stub::StubBackend::new())
        }
    }

    /// 当前后端名字，写进 ready 事件，便于 JVM 侧日志里一眼看出是哪种构建
    pub fn kind(&self) -> &'static str {
        match self {
            Backend::Stub(_) => "stub",
            #[cfg(feature = "livekit")]
            Backend::Live(_) => "livekit",
        }
    }

    pub async fn join(&mut self, url: &str, token: &str, mic: bool) -> MediaResult<()> {
        match self {
            Backend::Stub(b) => b.join(url, token, mic).await,
            #[cfg(feature = "livekit")]
            Backend::Live(b) => b.join(url, token, mic).await,
        }
    }

    pub fn set_muted(&mut self, muted: bool) -> MediaResult<()> {
        match self {
            Backend::Stub(b) => b.set_muted(muted),
            #[cfg(feature = "livekit")]
            Backend::Live(b) => b.set_muted(muted),
        }
    }

    pub async fn leave(&mut self) -> MediaResult<()> {
        match self {
            Backend::Stub(b) => b.leave().await,
            #[cfg(feature = "livekit")]
            Backend::Live(b) => b.leave().await,
        }
    }

    /// 供测试断言
    #[allow(dead_code)]
    pub fn state(&self) -> MediaState {
        match self {
            Backend::Stub(b) => b.state(),
            #[cfg(feature = "livekit")]
            Backend::Live(b) => b.state(),
        }
    }
}