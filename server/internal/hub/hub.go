// Package hub 管理在线连接与跨进程投递。
// 默认：直接内存投递（单进程）。
// AttachBus 后：SendToUser 除了本地投递，还会经 Redis pub/sub 广播到其他进程，
// 由持有 WebSocket 连接的 gateway 订阅并投给本进程的连接。
// 这是 logic（好友/群事件）能把帧推到 gateway 上在线客户端的唯一通道。
package hub

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"log"
	"sync"
	"time"

	"github.com/redis/go-redis/v9"
	"google.golang.org/protobuf/proto"

	"im/internal/metrics"
	"im/internal/pb"
)

// pushChannel 跨进程投递频道
const pushChannel = "im:push"

// Conn 一条已鉴权的客户端连接
type Conn interface {
	UID() string
	Platform() string
	// TokenVersion 建连时令牌里的版本号。账号改密/重置密码后服务端版本递增，
	// 版本落后的连接会被 KickStale 断开，做到「撤销后即时下线」。
	TokenVersion() int64
	Send(f *pb.Frame) bool // 非阻塞，失败返回 false（由写循环负责断开）
	Close()                // 主动断开（如账号被封禁）
}

// envelope 跨进程投递的载荷。一次广播带一组目标用户，避免群发时每个成员都走一次 Redis。
type envelope struct {
	Src  string   `json:"src"`  // 来源进程实例，收到自己的广播时忽略（避免重复投递）
	UIDs []string `json:"uids"` // 目标用户
	Body []byte   `json:"body"` // proto.Marshal(pb.Frame)
}

type Hub struct {
	mu    sync.RWMutex
	conns map[string]map[string]Conn // uid -> connId -> conn（多端登录）
	rdb   *redis.Client              // nil = 未接入总线，仅本进程内存投递
	self  string                     // 本进程实例 ID
}

func New() *Hub {
	return &Hub{conns: map[string]map[string]Conn{}, self: newInstanceID()}
}

// AttachBus 接入 Redis pub/sub 总线，使 SendToUser 可跨进程投递。
// subscribe=true 表示本进程持有 WebSocket 连接，需要接收其他进程广播过来的帧（gateway 才需要）。
// rdb 为 nil 时静默降级为纯内存投递。
func (h *Hub) AttachBus(ctx context.Context, rdb *redis.Client, subscribe bool) {
	if rdb == nil {
		return
	}
	h.mu.Lock()
	h.rdb = rdb
	h.mu.Unlock()
	if subscribe {
		go h.listen(ctx, rdb)
	}
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

// SendToUser 投递到该用户所有在线端；返回本进程是否有任一端发送成功。
// 同时广播到其他进程（接入总线时），因此 logic 进程也能把帧推给 gateway 上的连接。
func (h *Hub) SendToUser(uid string, f *pb.Frame) bool {
	return h.SendToUsers([]string{uid}, f)
}

// SendToUsers 一次投递给多个用户（写扩散场景），跨进程只广播一次
func (h *Hub) SendToUsers(uids []string, f *pb.Frame) bool {
	sent := false
	for _, uid := range uids {
		if h.deliverLocal(uid, f) {
			sent = true
		}
	}
	h.broadcast(uids, f)
	return sent
}

// deliverLocal 只投给本进程持有的连接
func (h *Hub) deliverLocal(uid string, f *pb.Frame) bool {
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

// encodeEnvelope 序列化跨进程投递载荷
func encodeEnvelope(src string, uids []string, f *pb.Frame) ([]byte, error) {
	body, err := proto.Marshal(f)
	if err != nil {
		return nil, err
	}
	return json.Marshal(envelope{Src: src, UIDs: uids, Body: body})
}

// decodeEnvelope 反序列化；返回 false 表示载荷损坏
func decodeEnvelope(payload []byte, env *envelope, f *pb.Frame) bool {
	if json.Unmarshal(payload, env) != nil || env.Src == "" || len(env.UIDs) == 0 {
		return false
	}
	return proto.Unmarshal(env.Body, f) == nil
}

// broadcast 跨进程广播；Redis 不可用时只降级（本地投递已独立完成）
func (h *Hub) broadcast(uids []string, f *pb.Frame) {
	h.mu.RLock()
	rdb, self := h.rdb, h.self
	h.mu.RUnlock()
	if rdb == nil || len(uids) == 0 {
		return
	}
	payload, err := encodeEnvelope(self, uids, f)
	if err != nil {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()
	if err := rdb.Publish(ctx, pushChannel, payload).Err(); err != nil {
		// 跨进程下行是「发了就不管」的 Pub/Sub：发布失败意味着这批帧对其它进程的
		// 在线连接永久丢失（消息能靠 seq 补拉，好友申请/群邀请这类事件不能），必须可见
		metrics.Inc("im_pubsub_publish_errors_total")
		log.Printf("[hub] 跨进程广播失败: %v", err)
	}
}

// listen 订阅总线并投递给本进程连接；连接断开后退避重建订阅
func (h *Hub) listen(ctx context.Context, rdb *redis.Client) {
	for {
		if ctx.Err() != nil {
			return
		}
		pubsub := rdb.Subscribe(ctx, pushChannel)
		ch := pubsub.Channel()
		for msg := range ch {
			var env envelope
			f := &pb.Frame{}
			if !decodeEnvelope([]byte(msg.Payload), &env, f) {
				continue
			}
			if env.Src == h.self { // 自己发的，已在 deliverLocal 处理过
				continue
			}
			for _, uid := range env.UIDs {
				h.deliverLocal(uid, f)
			}
		}
		_ = pubsub.Close()
		// 到这里说明订阅通道关闭（非正常退出），退避后重建
		select {
		case <-ctx.Done():
			return
		case <-time.After(time.Second):
		}
	}
}

// Kick 断开该用户在本进程的所有连接（如账号被封禁后踢下线）。
// 连接的 defer 会自行完成 hub.Remove，这里只负责关连接。
func (h *Hub) Kick(uid string) {
	h.mu.RLock()
	conns := make([]Conn, 0, len(h.conns[uid]))
	for _, c := range h.conns[uid] {
		conns = append(conns, c)
	}
	h.mu.RUnlock()
	for _, c := range conns {
		c.Close()
	}
}

// OnlineUIDs 当前本进程在线的 uid 快照（账号状态巡检用）
func (h *Hub) OnlineUIDs() []string {
	h.mu.RLock()
	defer h.mu.RUnlock()
	out := make([]string, 0, len(h.conns))
	for uid := range h.conns {
		out = append(out, uid)
	}
	return out
}

// KickStale 断开该用户在本进程中「令牌版本落后于 minVer」的连接，返回踢掉的连接数。
// 用于改密/重置密码/撤销令牌后让旧会话立即下线。
func (h *Hub) KickStale(uid string, minVer int64) int {
	h.mu.RLock()
	conns := make([]Conn, 0, len(h.conns[uid]))
	for _, c := range h.conns[uid] {
		conns = append(conns, c)
	}
	h.mu.RUnlock()
	n := 0
	for _, c := range conns {
		if c.TokenVersion() < minVer {
			c.Close()
			n++
		}
	}
	return n
}

// OnlineCount 当前在线连接用户数（仅本进程）
func (h *Hub) OnlineCount() int {
	h.mu.RLock()
	defer h.mu.RUnlock()
	return len(h.conns)
}

func newInstanceID() string {
	b := make([]byte, 8)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}
