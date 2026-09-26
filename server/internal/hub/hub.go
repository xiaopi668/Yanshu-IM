// Package hub 管理在线连接与跨进程投递。
// 单机模式：直接内存投递；多实例时通过 Redis pub/sub 广播（预留）。
package hub

import (
	"sync"

	"im/internal/pb"
)

// Conn 一条已鉴权的客户端连接
type Conn interface {
	UID() string
	Platform() string
	Send(f *pb.Frame) bool // 非阻塞，失败返回 false（由写循环负责断开）
}

type Hub struct {
	mu    sync.RWMutex
	conns map[string]map[string]Conn // uid -> connId -> conn（多端登录）
}

func New() *Hub {
	return &Hub{conns: map[string]map[string]Conn{}}
}

func (h *Hub) Add(uid, connID string, c Conn) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.conns[uid] == nil {
		h.conns[uid] = map[string]Conn{}
	}
	h.conns[uid][connID] = c
}

func (h *Hub) Remove(uid, connID string) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if m, ok := h.conns[uid]; ok {
		delete(m, connID)
		if len(m) == 0 {
			delete(h.conns, uid)
		}
	}
}

func (h *Hub) IsOnline(uid string) bool {
	h.mu.RLock()
	defer h.mu.RUnlock()
	return len(h.conns[uid]) > 0
}

// SendToUser 投递到该用户所有在线端；返回是否有任一端发送成功
func (h *Hub) SendToUser(uid string, f *pb.Frame) bool {
	h.mu.RLock()
	conns := make([]Conn, 0, len(h.conns[uid]))
	for _, c := range h.conns[uid] {
		conns = append(conns, c)
	}
	h.mu.RUnlock()
	sent := false
	for _, c := range conns {
		if c.Send(f) {
			sent = true
		}
	}
	return sent
}

// OnlineCount 当前在线连接用户数
func (h *Hub) OnlineCount() int {
	h.mu.RLock()
	defer h.mu.RUnlock()
	return len(h.conns)
}
