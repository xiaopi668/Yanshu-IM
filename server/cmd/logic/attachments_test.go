package main

// 附件下载票据与对象 key 白名单的单元测试。
// 这两处是「消息里不再携带登录 JWT」与「归档不能被越权下载」两处 P0 修复的落点，
// 一旦被改坏，等于把漏洞放回来，所以必须有测试守着。

import (
	"strconv"
	"strings"
	"testing"
	"time"

	"im/internal/config"
	"im/internal/storage"
)

func TestObjectKeyWhitelist(t *testing.T) {
	accept := []string{
		"image/20261003/0123456789abcdef",
		"file/20260101/ffffffffffffffff",
		"audio/20261231/0000000000000000",
		"video/20261003/abcdefabcdefabcd",
	}
	for _, k := range accept {
		if !storage.ValidObjectKey(k) {
			t.Errorf("合法 key 被拒绝: %q", k)
		}
	}
	// 重点：归档 key 与任何可被拼出来的路径都必须被拒绝
	reject := []string{
		"",
		"archive/20261003/0123456789abcdef.jsonl", // 归档：不能经 /v1/download 出去
		"image/2026/0123456789abcdef",             // 日期位数不对
		"image/20261003/0123456789ABCDEF",         // 大写 hex（生成端只产小写）
		"image/20261003/0123456789abcde",          // 长度不足
		"../../etc/passwd",
		"/v1/download?key=image/20261003/0123456789abcdef",
		"http://evil.example/x",
		"image/20261003/0123456789abcdef/../../x",
	}
	for _, k := range reject {
		if storage.ValidObjectKey(k) {
			t.Errorf("非法 key 被接受: %q", k)
		}
	}
}

func newTicketAPI(secret string) *apiv1 {
	return &apiv1{cfg: &config.Config{JWTSecret: secret}}
}

const testKey = "image/20261003/0123456789abcdef"

func TestTicketRoundTrip(t *testing.T) {
	a := newTicketAPI("unit-test-secret")
	if err := a.checkTicket(testKey, a.makeTicket(testKey)); err != nil {
		t.Fatalf("自签票据应校验通过，却失败: %v", err)
	}
}

func TestTicketIsBoundToKey(t *testing.T) {
	a := newTicketAPI("unit-test-secret")
	tk := a.makeTicket(testKey)
	other := "file/20261003/ffffffffffffffff"
	if err := a.checkTicket(other, tk); err == nil {
		t.Fatal("票据换一个 key 仍然通过 —— 票据必须与 key 绑定")
	}
}

func TestTicketRejectsTampering(t *testing.T) {
	a := newTicketAPI("unit-test-secret")
	key := testKey
	exp := strconv.FormatInt(time.Now().Add(time.Minute).UnixMilli(), 10)
	good := a.ticketMAC(key, exp)

	// 伪造签名
	if err := a.checkTicket(key, exp+".deadbeef"); err == nil {
		t.Error("伪造签名通过了校验")
	}
	// 把过期时间改远，但签名没跟着改（这是最典型的越权尝试）
	later := strconv.FormatInt(time.Now().Add(24*time.Hour).UnixMilli(), 10)
	if err := a.checkTicket(key, later+"."+good); err == nil {
		t.Error("篡改过期时间后仍通过校验")
	}
	// 格式非法
	if err := a.checkTicket(key, "no-dot-here"); err == nil {
		t.Error("缺少分隔符的票据通过了校验")
	}
}

func TestTicketRejectsOtherSecret(t *testing.T) {
	a := newTicketAPI("unit-test-secret")
	b := newTicketAPI("another-secret")
	if err := a.checkTicket(testKey, b.makeTicket(testKey)); err == nil {
		t.Fatal("用另一个密钥签的票据通过了校验")
	}
}

func TestTicketExpires(t *testing.T) {
	a := newTicketAPI("unit-test-secret")
	exp := strconv.FormatInt(time.Now().Add(-time.Minute).UnixMilli(), 10)
	tk := exp + "." + a.ticketMAC(testKey, exp)
	err := a.checkTicket(testKey, tk)
	if err == nil {
		t.Fatal("过期票据通过了校验")
	}
	if !strings.Contains(err.Error(), "过期") {
		t.Fatalf("过期票据应报「已过期」，实际: %v", err)
	}
}
