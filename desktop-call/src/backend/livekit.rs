//! 真实的 LiveKit 后端（Rust 官方 SDK）。
//!
//! 构建需要 cmake + clang（libwebrtc 由 webrtc-sys 构建），因此放在 feature gate 后面：
//!   cargo build --release --features livekit
//!
//! API 依据 livekit 0.10.0 源码确认：
//! - `Room::connect(url, token, RoomOptions) -> (Room, UnboundedReceiver<RoomEvent>)`
//! - 麦克风：`PlatformAudio::new()` + `LocalAudioTrack::create_audio_track("mic", audio.rtc_source())`
//! - 发布：`local_participant().publish_track(LocalTrack::Audio(track), TrackPublishOptions)`
//! - 远端音频由平台 ADM 自动播放，无需手动 attach
//! - 静音：`LocalAudioTrack::mute() / unmute()`

use super::{MediaError, MediaResult, MediaState};
use crate::emitter::Emitter;
use livekit::options::TrackPublishOptions;
use livekit::prelude::*;
use livekit::{PlatformAudio, Room, RoomOptions};
pub struct LiveKitBackend {
    room: Option<Room>,
    // 持有 PlatformAudio 才能保持 ADM 存活；drop 即关闭麦克风
    audio: Option<PlatformAudio>,
    track: Option<LocalAudioTrack>,
    state: MediaState,
    emitter: Emitter,
    joined: bool,
}

impl LiveKitBackend {
    pub fn new(emitter: Emitter) -> Self {
        Self {
            room: None,
            audio: None,
            track: None,
            state: MediaState::Disconnected,
            emitter,
            joined: false,
        }
    }

    pub fn state(&self) -> MediaState {
        self.state
    }

    fn publish_state(&self, state: &'static str) {
        self.emitter.state(state);
    }

    pub async fn join(&mut self, url: &str, token: &str, mic: bool) -> MediaResult<()> {
        if self.joined {
            // 复用同一个进程重连：先干净退出，避免残留轨道
            self.leave().await?;
        }
        self.state = MediaState::Connecting;

        let (room, events) = Room::connect(url, token, RoomOptions::default())
            .await
            .map_err(|e| MediaError(format!("加入房间失败: {e}")))?;

        if mic {
            let audio = PlatformAudio::new()
                .map_err(|e| MediaError(format!("无法访问麦克风: {e}（检查系统录音权限与设备）")))?;
            let track = LocalAudioTrack::create_audio_track("mic", audio.rtc_source());
            room.local_participant()
                .publish_track(
                    LocalTrack::Audio(track.clone()),
                    TrackPublishOptions {
                        source: TrackSource::Microphone,
                        ..Default::default()
                    },
                )
                .await
                .map_err(|e| MediaError(format!("发布麦克风失败: {e}")))?;
            self.audio = Some(audio);
            self.track = Some(track);
        }

        self.room = Some(room);
        self.state = MediaState::Connected;
        self.joined = true;

        // 后台消费 RoomEvent，把运行期状态回吐给 JVM。
        // 不消费的话：房间断了、正在重连，JVM 侧还以为在通话。
        let emitter = self.emitter.clone();
        tokio::spawn(async move {
            let mut events = events;
            while let Some(ev) = events.recv().await {
                let mapped: Option<&'static str> = match ev {
                    RoomEvent::Reconnecting => Some("reconnecting"),
                    RoomEvent::Reconnected => Some("connected"),
                    RoomEvent::Disconnected { .. } => Some("disconnected"),
                    _ => None,
                };
                if let Some(state) = mapped {
                    emitter.state(state);
                }
            }
        });

        Ok(())
    }

    pub fn set_muted(&mut self, muted: bool) -> MediaResult<()> {
        let track = self
            .track
            .as_ref()
            .ok_or_else(|| MediaError("麦克风尚未打开".into()))?;
        if muted {
            track.mute();
        } else {
            track.unmute();
        }
        Ok(())
    }

    pub async fn leave(&mut self) -> MediaResult<()> {
        // 先放掉轨道与 ADM（关闭麦克风），再断开房间
        self.track = None;
        self.audio = None;
        if let Some(room) = self.room.take() {
            room.close().await;
        }
        if self.joined {
            self.joined = false;
            self.state = MediaState::Disconnected;
            self.publish_state("disconnected");
        }
        Ok(())
    }
}