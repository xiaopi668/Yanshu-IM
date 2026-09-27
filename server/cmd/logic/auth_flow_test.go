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
	// 缺省回本站首页
	got, err := checkReturnTo(base, "")
	if err != nil || got != base+"/" {
		t.Fatalf("got %q err %v", got, err)
	}
	// 同源
	got, err = checkReturnTo(base, "https://im.example.com/chat?x=1#old")
	if err != nil {
		t.Fatalf("同源应放行: %v", err)
	}
	if strings.Contains(got, "#old") {
		t.Fatalf("应清掉旧 fragment: %q", got)
	}
	// 跨源一律拒绝
	for _, evil := range []string{
		"https://evil.com/",
		"https://im.example.com.evil.com/",
		"https://evil.com/https://im.example.com",
		"javascript:alert(1)",
	} {
		if _, err := checkReturnTo(base, evil); err == nil {
			t.Errorf("应当拒绝: %s", evil)
		}
	}
	// 相对地址（浏览器会当成同路径，无法保证同源）一律拒绝
	if _, err := checkReturnTo(base, "/chat"); err == nil {
		t.Fatal("相对地址应拒绝")
	}
}

func TestOidcCallbackURI(t *testing.T) {
	if got := oidcCallbackURI(base, "github"); got != base+"/v1/oidc/github/callback" {
		t.Fatalf("got %q", got)
	}
}
