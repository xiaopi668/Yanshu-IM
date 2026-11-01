//! 侧车与 JVM 之间的协议（JSON Lines，一行一个对象）。
//!
//! 设计要点：
//! - stdout **只**走协议，日志一律走 stderr —— 否则 JVM 侧解析会被日志污染；
//! - 命令与事件都用 tag 字段区分，未知命令回错误而不是崩；
//! - 协议保持极小：join / set_muted / leave / ping。

use serde::{Deserialize, Serialize};

#[derive(Debug, Deserialize)]
#[serde(tag = "cmd", rename_all = "snake_case")]
pub enum Command {
    /// 加入房间并打开麦克风
    Join {
        url: String,
        token: String,
        #[serde(default = "default_true")]
        mic: bool,
    },
    SetMuted { muted: bool },
    Leave,
    Ping,
    /// 仅用于本地/CI 冒烟：不连网，直接走一遍状态机
    Selftest,
}

fn default_true() -> bool {
    true
}

#[derive(Debug, Serialize, PartialEq, Eq)]
#[serde(tag = "evt", rename_all = "snake_case")]
pub enum Event {
    /// 进程就绪，可以接收命令。
    /// backend 告诉调用方这是 stub 还是真实媒体栈（测试与排障都要靠它区分）
    Ready { version: String, backend: &'static str },
    /// 连接状态：connecting / connected / reconnecting / disconnected
    State { state: &'static str },
    /// 可读错误。fatal=true 表示进程将退出，JVM 侧应上报失败而不是重试
    Error { message: String, fatal: bool },
    Pong,
    /// 自检（--selftest 或 selftest 命令）走完状态机后的收尾标记
    SelftestOk,
}

impl Event {
    pub fn to_line(&self) -> String {
        serde_json::to_string(self).unwrap_or_else(|_| "{\"evt\":\"error\",\"message\":\"encode failed\",\"fatal\":true}".into())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_join() {
        let c: Command = serde_json::from_str(r#"{"cmd":"join","url":"wss://x","token":"t"}"#).unwrap();
        match c {
            Command::Join { url, token, mic } => {
                assert_eq!(url, "wss://x");
                assert_eq!(token, "t");
                assert!(mic, "缺省应开麦");
            }
            _ => panic!("应为 join"),
        }
    }

    #[test]
    fn parses_unknown_as_error() {
        let r: Result<Command, _> = serde_json::from_str(r#"{"cmd":"nope"}"#);
        assert!(r.is_err(), "未知命令应返回错误，由上层回复 error 事件");
    }

    #[test]
    fn event_line_shape() {
        assert_eq!(Event::Pong.to_line(), r#"{"evt":"pong"}"#);
        assert_eq!(
            Event::State { state: "connected" }.to_line(),
            r#"{"evt":"state","state":"connected"}"#
        );
    }
}
