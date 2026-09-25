package hub

import (
	"testing"

	"im/internal/pb"
)

type fakeConn struct {
	uid   string
	plat  string
	sent  int
}

func (f *fakeConn) UID() string      { return f.uid }
func (f *fakeConn) Platform() string { return f.plat }
func (f *fakeConn) Send(frm *pb.Frame) bool { f.sent++; return true }

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
