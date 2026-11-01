//! 空后端：不连网，只走状态机。
//!
//! 用途：
//! - 没有 cmake/libwebrtc 的机器上开发与跑测试（CI 默认走这里）
//! - JVM 侧协议联调：把「连接成功」模拟出来，验证 spawn/解析/超时/销毁

use super::{MediaError, MediaResult, MediaState};
use std::sync::{Arc, Mutex};

pub struct StubBackend {
    state: Arc<Mutex<MediaState>>,
    muted: bool,
}

impl Default for StubBackend {
    fn default() -> Self {
        Self::new()
    }
}

impl StubBackend {
    pub fn new() -> Self {
        Self { state: Arc::new(Mutex::new(MediaState::Disconnected)), muted: false }
    }

    #[allow(dead_code)] // 仅测试断言用
    pub fn state(&self) -> MediaState {
        *self.state.lock().unwrap()
    }

    pub async fn join(&mut self, url: &str, token: &str, _mic: bool) -> MediaResult<()> {
        if url.is_empty() {
            return Err(MediaError("服务端未配置通话地址（livekit_url）".into()));
        }
        if token.is_empty() {
            return Err(MediaError("缺少房间令牌".into()));
        }
        *self.state.lock().unwrap() = MediaState::Connecting;
        // 模拟入会耗时，让 JVM 侧的超时/进度逻辑真的被执行到
        tokio::time::sleep(std::time::Duration::from_millis(80)).await;
        *self.state.lock().unwrap() = MediaState::Connected;
        Ok(())
    }

    pub fn set_muted(&mut self, muted: bool) -> MediaResult<()> {
        self.muted = muted;
        Ok(())
    }

    pub async fn leave(&mut self) -> MediaResult<()> {
        *self.state.lock().unwrap() = MediaState::Disconnected;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn join_then_leave() {
        let mut b = StubBackend::new();
        assert_eq!(b.state(), MediaState::Disconnected);
        b.join("wss://x", "t", true).await.unwrap();
        assert_eq!(b.state(), MediaState::Connected);
        b.leave().await.unwrap();
        assert_eq!(b.state(), MediaState::Disconnected);
    }

    #[tokio::test]
    async fn empty_url_is_rejected() {
        let mut b = StubBackend::new();
        let e = b.join("", "t", true).await.unwrap_err();
        assert!(e.0.contains("livekit_url"), "错误信息要能指导排查: {}", e.0);
    }
}
