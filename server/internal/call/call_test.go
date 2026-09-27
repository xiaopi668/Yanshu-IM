package call

import (
	"testing"
	"time"
)

func TestStartInviteBusy(t *testing.T) {
	m := NewManager("k", "s")
	if _, err := m.StartInvite("a", "b"); err != nil {
		t.Fatalf("首次邀请不应失败: %v", err)
	}
	if _, err := m.StartInvite("c", "b"); err != ErrBusy {
		t.Fatalf("被叫已有待接听邀请时应 busy, got %v", err)
	}
	// 同一个主叫重复邀请也应 busy（pending 按被叫索引）
	if _, err := m.StartInvite("a", "b"); err != ErrBusy {
		t.Fatalf("got %v", err)
	}
}

// TestPollExpired 修复前 PollExpired 返回 []string 且从未被调用，
// 导致被叫永远收 busy；这里覆盖清理与返回语义。
func TestPollExpired(t *testing.T) {
	m := NewManager("k", "s")
	inv, err := m.StartInvite("a", "b")
	if err != nil {
		t.Fatal(err)
	}
	if got := m.PollExpired(); len(got) != 0 {
		t.Fatalf("未超时不应清理: %v", got)
	}
	// 手动把创建时间推到超时之前
	m.mu.Lock()
	m.pending["b"].Created = time.Now().Add(-ringTimeout - time.Second)
	m.mu.Unlock()

	got := m.PollExpired()
	if len(got) != 1 || got[0].CallID != inv.CallID {
		t.Fatalf("应清理 1 条超时邀请, got %+v", got)
	}
	if got[0].FromUID != "a" || got[0].ToUID != "b" {
		t.Fatalf("需带主被叫信息以便推送 TIMEOUT, got %+v", got[0])
	}
	// 清理后被叫不再 busy
	if _, err := m.StartInvite("c", "b"); err != nil {
		t.Fatalf("超时后应可再次被邀请: %v", err)
	}
}

func TestAcceptAndEnd(t *testing.T) {
	m := NewManager("k", "s")
	inv, _ := m.StartInvite("a", "b")
	tokA, tokB, ok := m.Accept(inv.CallID, "b")
	if !ok || tokA == "" || tokB == "" {
		t.Fatalf("accept 失败 ok=%v", ok)
	}
	if m.Session(inv.CallID) == nil {
		t.Fatal("会话应已建立")
	}
	m.End(inv.CallID)
	if m.Session(inv.CallID) != nil {
		t.Fatal("End 后会话应释放，否则 calls 常驻内存")
	}
	// 释放 pending 之后同一被叫可再次接听
	if _, err := m.StartInvite("c", "b"); err != nil {
		t.Fatalf("接通后被叫应空闲: %v", err)
	}
}

func TestPollStaleSessions(t *testing.T) {
	m := NewManager("k", "s")
	inv, _ := m.StartInvite("a", "b")
	if _, _, ok := m.Accept(inv.CallID, "b"); !ok {
		t.Fatal("accept failed")
	}
	if got := m.PollStaleSessions(time.Hour); len(got) != 0 {
		t.Fatalf("刚建立的会话不应被清理: %v", got)
	}
	m.mu.Lock()
	m.calls[inv.CallID].Created = time.Now().Add(-2 * time.Hour)
	m.mu.Unlock()
	got := m.PollStaleSessions(time.Hour)
	if len(got) != 1 || got[0].CallID != inv.CallID {
		t.Fatalf("应清理僵尸会话: %+v", got)
	}
	if m.Session(inv.CallID) != nil {
		t.Fatal("清理后应不存在")
	}
}
