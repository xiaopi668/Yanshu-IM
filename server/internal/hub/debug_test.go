package hub

import (
	"testing"

	"im/internal/pb"
)

type fakeConn struct {
	uid    string
	plat   string
	sent   int
	closed bool
}

func (f *fakeConn) UID() string             { return f.uid }
func (f *fakeConn) Platform() string        { return f.plat }
func (f *fakeConn) Send(frm *pb.Frame) bool { f.sent++; return true }
func (f *fakeConn) Close()                  { f.closed = true }

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
