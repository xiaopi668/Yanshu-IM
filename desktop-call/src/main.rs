//! 雁书桌面端通话侧车。
//!
//! 职责：替 JVM 进程完成「加入 LiveKit 房间 + 采集麦克风 + 播放远端音频」。
//! JVM 侧只通过 **stdin/stdout 的 JSON Lines** 与它对话，不涉及 WebRTC 绑定。
//!
//! ```text
//! stdin  ← {"cmd":"join","url":"wss://…","token":"…"}
//! stdout → {"evt":"ready","version":"0.1.0","backend":"stub"}
//! stdout → {"evt":"state","state":"connecting"}
//! stdout → {"evt":"state","state":"connected"}
//! ```
//!
//! 约定：
//! - **stdout 只输出协议**，日志走 stderr（否则 JVM 侧解析会被日志污染）
//! - 退出码 0 = 正常结束；1 = 致命错误（JVM 侧据此上报而不是重试）
//! - 一个进程只服务一次通话；通话结束就退出，不复用

mod backend;
mod emitter;
mod proto;

use backend::{Backend, MediaError};
use emitter::Emitter;
use proto::{Command, Event};
use tokio::io::{AsyncBufReadExt, BufReader};

const VERSION: &str = env!("CARGO_PKG_VERSION");

/// 把事件写给 stdout（内部即时 flush、状态去重）
fn emit(em: &Emitter, evt: Event) {
    match evt {
        Event::State { state } => em.state(state),
        other => em.event(other),
    }
}

#[tokio::main(flavor = "current_thread")]
async fn main() {
    init_tracing();

    // 主循环、后端 leave()、后台 RoomEvent 任务共用同一个出口（内部去重）
    let emitter = Emitter::new();
    let mut backend = Backend::new(emitter.clone());

    emit(
        &emitter,
        Event::Ready {
            version: VERSION.to_string(),
            backend: backend.kind(),
        },
    );
    tracing::info!(backend = backend.kind(), "sidecar ready");

    let stdin = tokio::io::stdin();
    let mut lines = BufReader::new(stdin).lines();

    // 同一时刻只处理一条命令（通话本来就是串行的），外部用 async 单线程即可。
    let mut exit_code = 0;
    while let Ok(Some(line)) = lines.next_line().await {
        let line = line.trim();
        if line.is_empty() {
            continue;
        }
        let cmd: Command = match serde_json::from_str(line) {
            Ok(c) => c,
            Err(e) => {
                // 不崩：回一条错误，让 JVM 侧能看到是协议问题而不是"进程莫名退出"
                emit(
                    &emitter,
                    Event::Error {
                        message: format!("无法解析命令: {e}"),
                        fatal: false,
                    },
                );
                continue;
            }
        };

        match cmd {
            Command::Ping => emit(&emitter, Event::Pong),

            Command::Selftest => {
                // 本地/CI 冒烟：走一遍完整状态机，不需要真实网络
                emit(&emitter, Event::State { state: "connecting" });
                match backend.join("wss://selftest.invalid", "selftest-token", true).await {
                    Ok(()) => {
                        emit(&emitter, Event::State { state: "connected" });
                        let _ = backend.set_muted(true);
                        let _ = backend.leave().await;
                        emit(&emitter, Event::State { state: "disconnected" });
                        emit(&emitter, Event::SelftestOk);
                    }
                    Err(e) => emit(
                        &emitter,
                        Event::Error { message: e.0, fatal: false },
                    ),
                }
            }

            Command::Join { url, token, mic } => {
                emit(&emitter, Event::State { state: "connecting" });
                match backend.join(&url, &token, mic).await {
                    Ok(()) => emit(&emitter, Event::State { state: "connected" }),
                    Err(MediaError(msg)) => {
                        emit(&emitter, Event::Error { message: msg, fatal: true });
                        exit_code = 1;
                        break;
                    }
                }
            }

            Command::SetMuted { muted } => {
                if let Err(MediaError(msg)) = backend.set_muted(muted) {
                    emit(&emitter, Event::Error { message: msg, fatal: false });
                }
            }

            Command::Leave => {
                let _ = backend.leave().await;
                emit(&emitter, Event::State { state: "disconnected" });
                // 已经显式离开，收尾不要再重复一次（否则 JVM 侧会收到两条 disconnected）
                std::process::exit(0);
            }
        }
    }

    // 无论怎么结束，都要尽力释放麦克风
    let _ = backend.leave().await;
    std::process::exit(exit_code);
}

fn init_tracing() {
    use tracing_subscriber::EnvFilter;
    let filter = EnvFilter::try_from_env("YANSHU_CALL_LOG")
        .unwrap_or_else(|_| EnvFilter::new("info"));
    let _ = tracing_subscriber::fmt()
        .with_env_filter(filter)
        .with_writer(std::io::stderr)   // 关键：日志不能进 stdout
        .with_target(false)
        .without_time()
        .try_init();
}
