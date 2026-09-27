// gateway：WebSocket 长连接接入层。
// 职责：连接管理、鉴权、心跳、帧解码 → messaging。
package main

import (
	"context"
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"log"
	"net/http"
	"sync"
	"time"

	"github.com/gorilla/websocket"
	"github.com/redis/go-redis/v9"
	"google.golang.org/protobuf/proto"

	"im/internal/auth"
	"im/internal/call"
	"im/internal/config"
	"im/internal/hub"
	"im/internal/messaging"
	"im/internal/pb"
	"im/internal/store"
)

const (
	writeTimeout  = 10 * time.Second
	pongWait      = 60 * time.Second
	pingPeriod    = 25 * time.Second
	maxFrameSize  = 1 << 22 // 4MB（多媒体消息 base64 兜底）
	sendQueueSize = 512     // 每连接出站队列长度；写循环是该连接的唯一写者
)

// ---------- conn ----------

// conn 一条连接。写操作统一收敛到 writeLoop，避免多协程并发写同一 websocket，
// 也避免群发时被慢连接的同步写阻塞住发送方的读循环。
type conn struct {
	c         *websocket.Conn
	uid       string
	platform  string
	connID    string
	hub       *hub.Hub
	sendq     chan []byte
	done      chan struct{}
	closeOnce sync.Once
}

func (c *conn) UID() string      { return c.uid }
func (c *conn) Platform() string { return c.platform }

// Send 非阻塞入队；队列满说明消费不过来，直接断开该连接，
// 客户端重连后会按 seq 增量拉补，不会丢消息。
func (c *conn) Send(f *pb.Frame) bool {
	b, err := protoMarshal(f)
	if err != nil {
		return false
	}
	select {
	case <-c.done:
		return false
	default:
	}
	select {
	case c.sendq <- b:
		return true
	default:
		c.close()
		return false
	}
}

func (c *conn) close() {
	c.closeOnce.Do(func() {
		close(c.done)
		_ = c.c.Close()
	})
}

// Close hub.Conn 接口：被封禁等场景由服务端主动断开
func (c *conn) Close() { c.close() }

// writeLoop 连接的唯一写者，同时按 pingPeriod 发协议层 ping 探活
func (c *conn) writeLoop() {
	ticker := time.NewTicker(pingPeriod)
	defer ticker.Stop()
	for {
		select {
		case b := <-c.sendq:
			_ = c.c.SetWriteDeadline(time.Now().Add(writeTimeout))
			if err := c.c.WriteMessage(websocket.BinaryMessage, b); err != nil {
				c.close()
				return
			}
		case <-ticker.C:
			_ = c.c.SetWriteDeadline(time.Now().Add(writeTimeout))
			if err := c.c.WriteControl(websocket.PingMessage, nil, time.Now().Add(writeTimeout)); err != nil {
				c.close()
				return
			}
		case <-c.done:
			return
		}
	}
}

// ---------- server ----------

type server struct {
	cfg  *config.Config
	hub  *hub.Hub
	msg  *messaging.Service
	db   *sql.DB
	upgr websocket.Upgrader
	call *call.Manager
}

func main() {
	cfg := config.Load()
	if err := cfg.CheckSecrets(); err != nil {
		log.Fatalf("[gateway] %v", err)
	}
	db, err := store.NewMySQL(cfg.MySQLDSN)
	if err != nil {
		log.Fatalf("mysql: %v", err)
	}
	rdb := store.NewRedis(cfg.RedisAddr, cfg.RedisPass)
	h := hub.New()
	// 接入 Redis 总线：gateway 持有 WS 连接，既要广播也要订阅其他进程的投递
	h.AttachBus(context.Background(), rdb, true)
	msgSvc := messaging.New(db, rdb, h, 1)
	callMgr := call.NewManager(cfg.LiveKitAPIKey, cfg.LiveKitAPISecret)
	s := &server{
		cfg: cfg, hub: h, msg: msgSvc, db: db, call: callMgr,
		upgr: websocket.Upgrader{
			ReadBufferSize:  4096,
			WriteBufferSize: 4096,
			// 防跨站 WebSocket 劫持：未配置白名单时保持原有放行（原生客户端与本地开发）
			CheckOrigin: func(r *http.Request) bool {
				return cfg.OriginAllowed(r.Header.Get("Origin"))
			},
		},
	}
	mux := http.NewServeMux()
	mux.HandleFunc("/ws", s.handleWS)
	// 在线数上报（管理后台统计用）
	go func() {
		for {
			reportOnline(context.Background(), rdb, int64(s.hub.OnlineCount()))
			time.Sleep(30 * time.Second)
		}
	}()
	// 通话邀请超时：清理超时未接听的邀请并向双方推 CALL_TIMEOUT
	go s.callTimeoutLoop()
	// 封禁即时生效：管理后台封禁只改库，不踢的话在线连接照常收发
	go s.kickDisabledLoop()
	log.Printf("[gateway] listening on %s", cfg.GatewayAddr)
	log.Fatal(http.ListenAndServe(cfg.GatewayAddr, mux))
}

// kickDisabledLoop 周期扫描被封禁用户并踢下线。
// 一次全表捞 disabled 名单（user_state 里只有异常账号，通常很小），只对确实在线的做踢除。
func (s *server) kickDisabledLoop() {
	t := time.NewTicker(30 * time.Second)
	defer t.Stop()
	for range t.C {
		rows, err := s.db.Query(`SELECT uid FROM user_state WHERE disabled=1`)
		if err != nil {
			continue
		}
		var uids []string
		for rows.Next() {
			var u string
			if rows.Scan(&u) == nil {
				uids = append(uids, u)
			}
		}
		rows.Close()
		for _, uid := range uids {
			if s.hub.IsOnline(uid) {
				s.hub.Kick(uid)
				log.Printf("[gateway] 封禁用户已踢下线 uid=%s", uid)
			}
		}
	}
}

// callTimeoutLoop 周期清理超时未接听的邀请与僵死会话
func (s *server) callTimeoutLoop() {
	t := time.NewTicker(5 * time.Second)
	defer t.Stop()
	for range t.C {
		for _, inv := range s.call.PollExpired() {
			frame := &pb.Frame{Body: &pb.Frame_CallSignal{CallSignal: &pb.CallSignal{
				CallId: inv.CallID, Event: pb.CallEventType_CALL_TIMEOUT,
			}}}
			s.hub.SendToUser(inv.ToUID, frame)
			s.hub.SendToUser(inv.FromUID, frame)
			log.Printf("[call] invite timeout call=%s from=%s to=%s", inv.CallID, inv.FromUID, inv.ToUID)
		}
		for _, sess := range s.call.PollStaleSessions(4 * time.Hour) {
			s.hub.SendToUser(sess.A, &pb.Frame{Body: &pb.Frame_CallSignal{CallSignal: &pb.CallSignal{
				CallId: sess.CallID, Event: pb.CallEventType_CALL_HANGUP,
			}}})
			s.hub.SendToUser(sess.B, &pb.Frame{Body: &pb.Frame_CallSignal{CallSignal: &pb.CallSignal{
				CallId: sess.CallID, Event: pb.CallEventType_CALL_HANGUP,
			}}})
		}
	}
}

func (s *server) handleWS(w http.ResponseWriter, r *http.Request) {
	ws, err := s.upgr.Upgrade(w, r, nil)
	if err != nil {
		return
	}
	ws.SetReadLimit(maxFrameSize)
	// 协议层 ping 的 pong 回包刷新读超时
	ws.SetPongHandler(func(string) error {
		return ws.SetReadDeadline(time.Now().Add(pongWait))
	})
	c := &conn{c: ws, hub: s.hub, connID: randID(), sendq: make(chan []byte, sendQueueSize), done: make(chan struct{})}
	go c.writeLoop()

	defer func() {
		c.close()
		if c.uid != "" {
			s.hub.Remove(c.uid, c.connID)
		}
	}()

	// 鉴权必须 10s 内完成
	_ = ws.SetReadDeadline(time.Now().Add(10 * time.Second))
	authed := false
	for {
		mt, data, err := ws.ReadMessage()
		if err != nil {
			return
		}
		if mt != websocket.BinaryMessage {
			continue
		}
		f := &pb.Frame{}
		if err := protoUnmarshal(data, f); err != nil {
			return
		}

		if !authed {
			ar := f.GetAuthReq()
			if ar == nil {
				return
			}
			claims, err := auth.ParseToken(s.cfg.JWTSecret, ar.Token)
			if err != nil {
				c.Send(&pb.Frame{Body: &pb.Frame_AuthResp{AuthResp: &pb.AuthResp{Ok: false, Reason: "unauthorized"}}})
				return
			}
			c.uid, c.platform = claims.UID, ar.Platform
			// 封禁检查
			var disabled int
			if err := s.db.QueryRowContext(r.Context(),
				`SELECT disabled FROM user_state WHERE uid=?`, c.uid).Scan(&disabled); err == nil && disabled == 1 {
				c.Send(&pb.Frame{Body: &pb.Frame_AuthResp{AuthResp: &pb.AuthResp{Ok: false, Reason: "account disabled"}}})
				return
			}
			s.hub.Add(c.uid, c.connID, c)
			log.Printf("[gateway] conn authed uid=%s conn=%s", c.uid, c.connID)
			authed = true
			_ = ws.SetReadDeadline(time.Now().Add(pongWait))
			maxSeqs, _ := s.msg.MaxSeqs(r.Context(), c.uid)
			if maxSeqs == nil {
				maxSeqs = map[string]uint64{}
			}
			c.Send(&pb.Frame{Body: &pb.Frame_AuthResp{AuthResp: &pb.AuthResp{Ok: true, MaxSeqs: maxSeqs}}})
			continue
		}

		_ = ws.SetReadDeadline(time.Now().Add(pongWait))
		s.handleFrame(r.Context(), c, f)
	}
}

func (s *server) handleFrame(ctx context.Context, c *conn, f *pb.Frame) {
	switch b := f.Body.(type) {
	case *pb.Frame_Heartbeat:
		c.Send(&pb.Frame{Body: &pb.Frame_Heartbeat{Heartbeat: &pb.Heartbeat{}}})

	case *pb.Frame_MsgSend:
		m := b.MsgSend
		msgID, seq, err := s.msg.Send(ctx, c.uid, m)
		if err != nil {
			log.Printf("[gateway] send err: %v", err)
			return
		}
		c.Send(&pb.Frame{Body: &pb.Frame_MsgAck{MsgAck: &pb.MsgAck{
			ClientMsgId: m.ClientMsgId, Seq: seq, ServerMsgId: msgID, ConversationId: m.ConversationId,
		}}})

	case *pb.Frame_MsgPullReq:
		p := b.MsgPullReq
		resp, err := s.msg.Pull(ctx, c.uid, p.ConversationId, p.AfterSeq, p.Limit)
		if err != nil {
			log.Printf("[gateway] pull err: %v", err)
			return
		}
		c.Send(&pb.Frame{Body: &pb.Frame_MsgPullResp{MsgPullResp: resp}})

	case *pb.Frame_MsgRead:
		m := b.MsgRead
		_ = s.msg.MarkRead(ctx, c.uid, m.ConversationId, m.UpToSeq)

	case *pb.Frame_CallSignal:
		s.handleCallSignal(ctx, c, b.CallSignal)

	default:
	}
}

// ---------- helpers ----------

// handleCallSignal 1v1 通话信令状态机
func (s *server) handleCallSignal(ctx context.Context, c *conn, sig *pb.CallSignal) {
	log.Printf("[call] sig from=%s event=%d call=%s text=%s", c.uid, sig.Event, sig.CallId, sig.Text)
	switch sig.Event {
	case pb.CallEventType_CALL_INVITE:
		if sig.Text == "" { // text 携带被叫 UID
			return
		}
		inv, err := s.call.StartInvite(c.uid, sig.Text)
		log.Printf("[call] invite result: inv=%v err=%v online=%v", inv != nil, err, s.hub.IsOnline(sig.Text))
		if err != nil {
			// 忙碌：回给主叫 BUSY
			c.Send(&pb.Frame{Body: &pb.Frame_CallSignal{CallSignal: &pb.CallSignal{
				CallId: sig.CallId, Event: pb.CallEventType_CALL_BUSY,
			}}})
			return
		}
		// 通知被叫响铃
		okSend := s.hub.SendToUser(sig.Text, &pb.Frame{Body: &pb.Frame_CallSignal{CallSignal: &pb.CallSignal{
			CallId: inv.CallID, Event: pb.CallEventType_CALL_INVITE, Text: c.uid,
		}}})
		log.Printf("[call] invite sent to %s: %v", sig.Text, okSend)

	case pb.CallEventType_CALL_ACCEPT:
		inv := s.call.InviteOf(c.uid)
		if inv == nil || inv.CallID != sig.CallId {
			return
		}
		tokenA, tokenB, ok := s.call.Accept(sig.CallId, c.uid)
		if !ok {
			return
		}
		// 双方各拿房间与 token
		s.hub.SendToUser(inv.FromUID, &pb.Frame{Body: &pb.Frame_CallSignal{CallSignal: &pb.CallSignal{
			CallId: sig.CallId, Event: pb.CallEventType_CALL_ACCEPT,
			RoomName: inv.RoomName, LivekitToken: tokenA,
		}}})
		c.Send(&pb.Frame{Body: &pb.Frame_CallSignal{CallSignal: &pb.CallSignal{
			CallId: sig.CallId, Event: pb.CallEventType_CALL_ACCEPT,
			RoomName: inv.RoomName, LivekitToken: tokenB,
		}}})

	case pb.CallEventType_CALL_REJECT:
		if inv := s.call.InviteOf(c.uid); inv != nil && inv.CallID == sig.CallId {
			s.call.Cancel(sig.CallId)
			s.hub.SendToUser(inv.FromUID, &pb.Frame{Body: &pb.Frame_CallSignal{CallSignal: &pb.CallSignal{
				CallId: sig.CallId, Event: pb.CallEventType_CALL_REJECT, Text: sig.Text,
			}}})
		}

	case pb.CallEventType_CALL_CANCEL:
		if inv := s.call.InviteOf(sig.Text); inv != nil && inv.CallID == sig.CallId {
			s.call.Cancel(sig.CallId)
			s.hub.SendToUser(sig.Text, &pb.Frame{Body: &pb.Frame_CallSignal{CallSignal: &pb.CallSignal{
				CallId: sig.CallId, Event: pb.CallEventType_CALL_CANCEL,
			}}})
		}

	case pb.CallEventType_CALL_HANGUP:
		if sess := s.call.Session(sig.CallId); sess != nil {
			peer := sess.A
			if c.uid == sess.A {
				peer = sess.B
			}
			s.call.End(sig.CallId) // 释放会话，避免 calls 常驻内存
			s.hub.SendToUser(peer, &pb.Frame{Body: &pb.Frame_CallSignal{CallSignal: &pb.CallSignal{
				CallId: sig.CallId, Event: pb.CallEventType_CALL_HANGUP,
			}}})
		}
	}
}

// randID 连接 ID：16 位随机 hex，避免纳秒时间戳在同进程内碰撞导致 hub 条目互相覆盖
func randID() string {
	b := make([]byte, 8)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

// reportOnline gateway 周期上报在线数到 Redis（供管理后台统计）
func reportOnline(ctx context.Context, rdb *redis.Client, count int64) {
	_ = rdb.Set(ctx, "im:online", count, 2*time.Minute).Err()
}

func protoMarshal(f *pb.Frame) ([]byte, error) {
	return proto.Marshal(f)
}

func protoUnmarshal(b []byte, f *pb.Frame) error {
	return proto.Unmarshal(b, f)
}
