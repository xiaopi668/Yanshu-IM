//! 协议事件输出：stdout 的唯一写入口。
//!
//! 为什么需要它：状态事件有**三个来源** —— 主循环、后端 leave()、后台 RoomEvent 任务。
//! 各自直写 stdout 会重复（实测 leave 时连发三条 disconnected），JVM 侧日志很脏。
//! 这里用一把锁 + 「上一次状态」把去重收在一处，同时保证写行原子。

use crate::proto::Event;
use std::io::Write;
use std::sync::{Arc, Mutex};

struct Inner {
    out: std::io::Stdout,
    last_state: Option<&'static str>,
}

#[derive(Clone)]
pub struct Emitter {
    inner: Arc<Mutex<Inner>>,
}

impl Emitter {
    pub fn new() -> Self {
        Self {
            inner: Arc::new(Mutex::new(Inner {
                out: std::io::stdout(),
                last_state: None,
            })),
        }
    }

    /// 写一条非状态事件（ready / pong / error / selftest_ok）
    pub fn event(&self, evt: Event) {
        if let Ok(mut g) = self.inner.lock() {
            let _ = writeln!(g.out, "{}", evt.to_line());
            let _ = g.out.flush();
        }
    }

    /// 写一条状态事件，同一种状态连续出现只写一次
    pub fn state(&self, state: &'static str) {
        if let Ok(mut g) = self.inner.lock() {
            if g.last_state == Some(state) {
                return;
            }
            g.last_state = Some(state);
            let _ = writeln!(g.out, "{}", Event::State { state }.to_line());
            let _ = g.out.flush();
        }
    }
}
