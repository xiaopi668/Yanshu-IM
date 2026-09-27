package hub

import (
	"testing"

	"im/internal/pb"
)

func heartbeat() *pb.Frame {
	return &pb.Frame{Body: &pb.Frame_Heartbeat{Heartbeat: &pb.Heartbeat{}}}
}

func TestEnvelopeRoundTrip(t *testing.T) {
	src := "inst-a"
	payload, err := encodeEnvelope(src, []string{"u1", "u2"}, heartbeat())
	if err != nil {
		t.Fatalf("encode: %v", err)
	}
	var env envelope
	f := &pb.Frame{}
	if !decodeEnvelope(payload, &env, f) {
		t.Fatal("decode failed")
	}
	if env.Src != src || len(env.UIDs) != 2 || env.UIDs[0] != "u1" {
		t.Fatalf("envelope = %+v", env)
	}
	if f.GetHeartbeat() == nil {
		t.Fatal("frame body 丢失")
	}
}

func TestDecodeEnvelopeRejectsGarbage(t *testing.T) {
	for _, payload := range []string{
		`not json`,
		`{"uids":["u1"],"body":"AAAA"}`, // 缺 src
		`{"src":"a","body":"AAAA"}`,     // 缺 uids
		`{"src":"a","uids":[],"body":"AAAA"}`,
	} {
		var env envelope
		if decodeEnvelope([]byte(payload), &env, &pb.Frame{}) {
			t.Errorf("payload %q 应当被拒绝", payload)
		}
	}
}

// TestSendToUserLocalDelivery 确认未接入总线时退化为纯内存投递
func TestSendToUserLocalDelivery(t *testing.T) {
	h := New()
	c := &fakeConn{uid: "u1"}
	h.Add("u1", "c1", c)
	if !h.SendToUser("u1", heartbeat()) {
		t.Fatal("本地投递应成功")
	}
	if c.sent != 1 {
		t.Fatalf("sent = %d", c.sent)
	}
	if h.SendToUser("nobody", heartbeat()) {
		t.Log("无连接时返回 false（预期）")
	}
}

// TestSelfIsolation 确认收到自己广播时会跳过（否则同一连接会被投两次），
// 而其他实例收到则正常投递。
func TestSelfIsolation(t *testing.T) {
	a, b := New(), New()
	ca, cb := &fakeConn{uid: "u1"}, &fakeConn{uid: "u1"}
	a.Add("u1", "c1", ca)
	b.Add("u1", "c1", cb)

	payload, err := encodeEnvelope(a.self, []string{"u1"}, heartbeat())
	if err != nil {
		t.Fatal(err)
	}
	var env envelope
	f := &pb.Frame{}
	if !decodeEnvelope(payload, &env, f) {
		t.Fatal("decode failed")
	}
	if env.Src != a.self {
		t.Fatalf("src = %s, want %s", env.Src, a.self)
	}
	// 来源即自己 → 跳过，不做第二次本地投递
	if env.Src == a.self && ca.sent != 0 {
		t.Fatalf("自己广播不应重复投递本地, sent=%d", ca.sent)
	}
	// 其他实例 → 投递
	if env.Src != b.self {
		for _, uid := range env.UIDs {
			b.deliverLocal(uid, f)
		}
	}
	if cb.sent != 1 {
		t.Fatalf("其他实例应投递成功, sent=%d", cb.sent)
	}
}

// TestSendToUsers 一次投递多个目标，本地逐个命中
func TestSendToUsers(t *testing.T) {
	h := New()
	a, b := &fakeConn{uid: "u1"}, &fakeConn{uid: "u2"}
	h.Add("u1", "c1", a)
	h.Add("u2", "c2", b)
	if !h.SendToUsers([]string{"u1", "u2", "u3"}, heartbeat()) {
		t.Fatal("至少应有两端成功")
	}
	if a.sent != 1 || b.sent != 1 {
		t.Fatalf("a=%d b=%d", a.sent, b.sent)
	}
}
