package main

import (
	"strings"
	"testing"

	"im/internal/siteconf"
)

const base = "https://im.example.com"

func provider(redirects ...string) *siteconf.OIDCProvider {
	return &siteconf.OIDCProvider{Name: "github", RedirectURIs: redirects}
}

// TestCheckRedirectURI 关闭「只校验 http(s) 前缀」的开放重定向：
// 攻击者构造 redirect_uri=https://evil.com 可把签发的 JWT 直接送走（账号接管）。
func TestCheckRedirectURI(t *testing.T) {
	ours := oidcCallbackURI(base, "github")

	// 本站固定回调
	if err := checkRedirectURI(ours, ours, provider()); err != nil {
		t.Fatalf("本站回调应放行: %v", err)
	}
	// 缺省
	if err := checkRedirectURI(ours, "", provider()); err == nil {
		t.Fatal("空 redirect_uri 应拒绝")
	}
	// 各种攻击形态
	for _, evil := range []string{
		"https://evil.com/steal",
		"https://evil.com" + ours,
		"http://evil.com/v1/oidc/github/callback",
		"javascript:alert(1)",
		"//evil.com",
		"/v1/oidc/github/callback", // 相对地址，浏览器行为不可控
	} {
		if err := checkRedirectURI(ours, evil, provider()); err == nil {
			t.Errorf("应当拒绝: %s", evil)
		}
	}
	// 显式白名单放行
	allow := "https://spa.example.com/callback"
	if err := checkRedirectURI(ours, allow, provider(allow)); err != nil {
		t.Fatalf("白名单内应放行: %v", err)
	}
	if err := checkRedirectURI(ours, "https://other.example.com/cb", provider(allow)); err == nil {
		t.Fatal("白名单外应拒绝")
	}
	// 白名单匹配必须整串（含 scheme），不能只比 host
	if err := checkRedirectURI(ours, "http://spa.example.com/callback", provider(allow)); err == nil {
		t.Fatal("协议不同不应放行")
	}
}

func TestCheckReturnTo(t *testing.T) {
	// 缺省落在本站的令牌展示页
	got, err := checkReturnTo(base, "", nil)
	if err != nil || got != base+"/oidc-done" {
		t.Fatalf("got %q err %v", got, err)
	}
	// 同源
	got, err = checkReturnTo(base, "https://im.example.com/chat?x=1#old", nil)
	if err != nil {
		t.Fatalf("同源应放行: %v", err)
	}
	if strings.Contains(got, "#old") {
		t.Fatalf("应清掉旧 fragment: %q", got)
	}
	// 跨源一律拒绝（没有白名单时）
	for _, evil := range []string{
		"https://evil.com/",
		"https://im.example.com.evil.com/",
		"https://evil.com/https://im.example.com",
		"javascript:alert(1)",
	} {
		if _, err := checkReturnTo(base, evil, nil); err == nil {
			t.Errorf("应当拒绝: %s", evil)
		}
	}
	// 相对地址（浏览器会当成同路径，无法保证同源）一律拒绝
	if _, err := checkReturnTo(base, "/chat", nil); err == nil {
		t.Fatal("相对地址应拒绝")
	}
	// scheme 降级：https 站点不允许把令牌送到同 host 的 http
	if _, err := checkReturnTo(base, "http://im.example.com/chat", nil); err == nil {
		t.Fatal("http 降级应拒绝")
	}
}

// TestCheckReturnToAllowlist 覆盖「让令牌自动交回客户端」的白名单：
// 桌面端本地回环监听器（端口任意）与 Android 自定义 scheme。
func TestCheckReturnToAllowlist(t *testing.T) {
	allow := []string{"http://127.0.0.1", "http://localhost", "yanshu:"}

	// 本地回环：端口任意
	for _, ok := range []string{
		"http://127.0.0.1:38123/",
		"http://127.0.0.1:38123/oidc?x=1",
		"http://localhost:9999/",
	} {
		if _, err := checkReturnTo(base, ok, allow); err != nil {
			t.Errorf("白名单内应放行 %s: %v", ok, err)
		}
	}
	// Android 自定义 scheme
	if _, err := checkReturnTo(base, "yanshu://oidc/callback", allow); err != nil {
		t.Errorf("自定义 scheme 应放行: %v", err)
	}
	// 白名单未配置时，回环同样拒绝（默认安全）
	if _, err := checkReturnTo(base, "http://127.0.0.1:38123/", nil); err == nil {
		t.Error("未配置白名单时应拒绝回环地址")
	}
	// 前缀必须止于边界：否则 127.0.0.1 会连 127.0.0.1.evil.com 一起放行（开放重定向）
	if _, err := checkReturnTo(base, "http://127.0.0.1.evil.com/", allow); err == nil {
		t.Error("127.0.0.1.evil.com 不应被前缀放行")
	}
	if _, err := checkReturnTo(base, "http://localhost.evil.com/", allow); err == nil {
		t.Error("localhost.evil.com 不应被前缀放行")
	}
	// 其它 scheme 不在白名单内 → 拒绝
	if _, err := checkReturnTo(base, "myapp://steal", allow); err == nil {
		t.Error("未列入白名单的 scheme 应拒绝")
	}
}

// TestReturnUsesFragment 令牌投递方式：同源页面用 fragment，
// 回环监听器与自定义 scheme 必须用 query（它们根本收不到 fragment）。
func TestReturnUsesFragment(t *testing.T) {
	cases := []struct {
		target string
		wrap   bool
	}{
		{"https://im.example.com/chat", true},
		{"https://im.example.com/", true},
		{"http://127.0.0.1:38123/", false},
		{"http://localhost:9999/", false},
		{"yanshu://oidc/callback", false},
	}
	for _, c := range cases {
		if got := returnUsesFragment(base, c.target); got != c.wrap {
			t.Errorf("returnUsesFragment(%s) = %v，期望 %v", c.target, got, c.wrap)
		}
	}
}

func TestOidcCallbackURI(t *testing.T) {
	if got := oidcCallbackURI(base, "github"); got != base+"/v1/oidc/github/callback" {
		t.Fatalf("got %q", got)
	}
}
