package hub

import (
	"testing"

	"im/internal/pb"
)

type fakeConn struct {
	uid    string
	plat   string
	ver    int64
	sent   int
	closed bool
}

func (f *fakeConn) UID() string             { return f.uid }
func (f *fakeConn) Platform() string        { return f.plat }
func (f *fakeConn) TokenVersion() int64     { return f.ver }
func (f *fakeConn) Send(frm *pb.Frame) bool { f.sent++; return true }
func (f *fakeConn) Close()                  { f.closed = true }

// KickStale 是「令牌撤销后即时下线」的落点：只踢版本落后的连接
func TestKickStale(t *testing.T) {
	h := New()
	stale := &fakeConn{uid: "u1", ver: 0}
	fresh := &fakeConn{uid: "u2", ver: 3}
	h.Add("u1", "c1", stale)
	h.Add("u2", "c2", fresh)

	if n := h.KickStale("u1", 1); n != 1 || !stale.closed {
		t.Fatalf("版本落后的连接应被踢下线: n=%d closed=%v", n, stale.closed)
	}
	if n := h.KickStale("u2", 3); n != 0 || fresh.closed {
		t.Fatalf("版本相同的连接不应被踢: n=%d closed=%v", n, fresh.closed)
	}
	if n := h.KickStale("nobody", 9); n != 0 {
		t.Fatalf("不在线的用户不应报踢除: %d", n)
	}
}

func TestOnlineUIDs(t *testing.T) {
	h := New()
	h.Add("u1", "c1", &fakeConn{uid: "u1"})
	h.Add("u1", "c2", &fakeConn{uid: "u1"})
	h.Add("u2", "c3", &fakeConn{uid: "u2"})
	if got := len(h.OnlineUIDs()); got != 2 {
		t.Fatalf("同一用户多连接应只算一个 uid，得到 %d", got)
	}
}

func TestHubSend(t *testing.T) {
	h := New()
	a := &fakeConn{uid: "u1"}
	b := &fakeConn{uid: "u2"}
	h.Add("u1", "c1", a)
	h.Add("u2", "c2", b)
	if !h.IsOnline("u1") || !h.IsOnline("u2") {
		t.Fatal("not online")
	}
	h.SendToUser("u2", &pb.Frame{})
	if b.sent != 1 {
		t.Fatalf("u2 got %d", b.sent)
	}
	h.SendToUser("u1", &pb.Frame{})
	if a.sent != 1 {
		t.Fatalf("u1 got %d", a.sent)
	}
	t.Log("hub OK")
}

// TestHubKick 账号被封禁后必须能把在线连接断开（修复前封禁只改库，连接照常在线）
func TestHubKick(t *testing.T) {
	h := New()
	a1 := &fakeConn{uid: "u1"}
	a2 := &fakeConn{uid: "u1"}
	other := &fakeConn{uid: "u2"}
	h.Add("u1", "c1", a1)
	h.Add("u1", "c2", a2)
	h.Add("u2", "c3", other)

	h.Kick("u1")
	if !a1.closed || !a2.closed {
		t.Fatal("被封禁用户的所有端都应被关闭")
	}
	if other.closed {
		t.Fatal("不能误伤其他用户")
	}
}
