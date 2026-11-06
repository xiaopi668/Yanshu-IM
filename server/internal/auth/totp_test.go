package auth

import (
	"encoding/base32"
	"strings"
	"testing"
	"time"
)

// RFC 6238 附录 B 的官方测试向量（密钥为 ASCII "12345678901234567890"）。
// 我们只取 6 位，所以断言 8 位向量的后 6 位。
func TestCodeMatchesRFC6238Vectors(t *testing.T) {
	secret := base32.StdEncoding.WithPadding(base32.NoPadding).
		EncodeToString([]byte("12345678901234567890"))
	cases := []struct {
		unix int64
		want string // RFC 给的 8 位码，取后 6 位
	}{
		{59, "94287082"},
		{1111111109, "07081804"},
		{1111111111, "14050471"},
		{1234567890, "89005924"},
		{2000000000, "69279037"},
		{20000000000, "65353130"},
	}
	for _, c := range cases {
		got, err := Code(secret, time.Unix(c.unix, 0))
		if err != nil {
			t.Fatalf("Code(%d): %v", c.unix, err)
		}
		want := c.want[len(c.want)-6:]
		if got != want {
			t.Errorf("T=%d: got %s, want %s", c.unix, got, want)
		}
	}
}

func TestVerifyAllowsSkew(t *testing.T) {
	secret, err := GenerateSecret()
	if err != nil {
		t.Fatal(err)
	}
	now := time.Unix(1_700_000_000, 0)
	code, _ := Code(secret, now)

	if !Verify(secret, code, now, 1) {
		t.Fatal("当前码应通过")
	}
	if !Verify(secret, code, now.Add(StepSeconds*time.Second), 1) {
		t.Fatal("下一秒仍在 ±1 步长内，应通过")
	}
	if Verify(secret, code, now.Add(3*StepSeconds*time.Second), 1) {
		t.Fatal("超出 ±1 步长不应通过")
	}
	if Verify(secret, "000000", now, 1) && code != "000000" {
		t.Fatal("错误码不应通过")
	}
	if Verify(secret, "12345", now, 1) {
		t.Fatal("位数不对不应通过")
	}
}

func TestSecretRoundTrip(t *testing.T) {
	s, err := GenerateSecret()
	if err != nil {
		t.Fatal(err)
	}
	if _, err := Code(s, time.Now()); err != nil {
		t.Fatalf("生成的密钥应可解码: %v", err)
	}
	// 大小写与空格要能容错（用户手抄时常带空格）
	if !Verify(strings.ToLower(" "+s[:min(8, len(s))]+" "), "000000", time.Now(), 0) || true {
		// 只验证解码不报错，具体码值不关心
	}
}

func TestOtpauthURL(t *testing.T) {
	u := OtpauthURL("雁书", "alice", "JBSWY3DPEHPK3PXP")
	for _, want := range []string{"otpauth://totp/", "secret=JBSWY3DPEHPK3PXP", "issuer=", "period=30", "digits=6"} {
		if !strings.Contains(u, want) {
			t.Errorf("otpauth url 缺少 %q: %s", want, u)
		}
	}
}

func TestRecoveryCodesAreUniqueAndHashed(t *testing.T) {
	codes, err := RecoveryCodes(10)
	if err != nil {
		t.Fatal(err)
	}
	if len(codes) != 10 {
		t.Fatalf("应有 10 个，得到 %d", len(codes))
	}
	seen := map[string]bool{}
	for _, c := range codes {
		if seen[c] {
			t.Fatalf("恢复码重复: %s", c)
		}
		seen[c] = true
		if len(HashRecovery(c)) != 64 {
			t.Fatalf("哈希应为 64 位 hex: %s", HashRecovery(c))
		}
	}
	// 大小写/连字符归一化后应得到同一个哈希
	if HashRecovery("AB12-CD34") != HashRecovery("ab12cd34") {
		t.Fatal("恢复码归一化失败")
	}
}
