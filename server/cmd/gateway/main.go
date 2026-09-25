// gateway：WebSocket 长连接接入层。
// 职责：连接管理、鉴权、心跳、帧解码 → messaging。
package main

import (
	"context"
	"database/sql"
	"log"
	"net/http"
	"strconv"
	"sync"
	"time"

	"github.com/gorilla/websocket"
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
)

// ---------- conn ----------

type conn struct {
	c        *websocket.Conn
	uid      string
	platform string
	connID   string
	hub      *hub.Hub
	mu       sync.Mutex
	closed   bool
}

func (c *conn) UID() string      { return c.uid }
func (c *conn) Platform() string { return c.platform }

func (c *conn) Send(f *pb.Frame) bool {
	b, err := protoMarshal(f)
	if err != nil {
		return false
	}
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.closed {
		return false
	}
	_ = c.c.SetWriteDeadline(time.Now().Add(writeTimeout))
	return c.c.WriteMessage(websocket.BinaryMessage, b) == nil
}

func (c *conn) close() {
	c.mu.Lock()
	if c.closed {
		c.mu.Unlock()
		return
	}
	c.closed = true
	c.mu.Unlock()
	_ = c.c.Close()
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
	db, err := store.NewMySQL(cfg.MySQLDSN)
	if err != nil {
		log.Fatalf("mysql: %v", err)
	}
	rdb := store.NewRedis(cfg.RedisAddr, cfg.RedisPass)
	h := hub.New()
	msgSvc := messaging.New(db, rdb, h, 1)
	callMgr := call.NewManager(cfg.LiveKitAPIKey, cfg.LiveKitAPISecret)
	s := &server{
		cfg: cfg, hub: h, msg: msgSvc, db: db, call: callMgr,
		upgr: websocket.Upgrader{
			ReadBufferSize:  4096,
			WriteBufferSize: 4096,
			CheckOrigin:     func(r *http.Request) bool { return true },
		},
	}
	mux := http.NewServeMux()
	mux.HandleFunc("/ws", s.handleWS)
	log.Printf("[gateway] listening on %s", cfg.GatewayAddr)
	log.Fatal(http.ListenAndServe(cfg.GatewayAddr, mux))
}

func (s *server) handleWS(w http.ResponseWriter, r *http.Request) {
	ws, err := s.upgr.Upgrade(w, r, nil)
	if err != nil {
		return
	}
	ws.SetReadLimit(maxFrameSize)
	c := &conn{c: ws, hub: s.hub, connID: randID()}

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
			s.hub.SendToUser(peer, &pb.Frame{Body: &pb.Frame_CallSignal{CallSignal: &pb.CallSignal{
				CallId: sig.CallId, Event: pb.CallEventType_CALL_HANGUP,
			}}})
		}
	}
}

// 简单连接 ID：纳秒级时间戳足够区分本进程内连接
func randID() string {
	return strconv.FormatInt(time.Now().UnixNano(), 10)
}

func protoMarshal(f *pb.Frame) ([]byte, error) {
	return proto.Marshal(f)
}

func protoUnmarshal(b []byte, f *pb.Frame) error {
	return proto.Unmarshal(b, f)
}

