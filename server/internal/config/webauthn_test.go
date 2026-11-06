package config

import (
	"reflect"
	"testing"
)

// RP ID 推导错了，浏览器会直接抛 SecurityError，而且提示里不会说是哪个配置错了。
// 这里把常见部署形态钉住：带端口、带路径、空值、显式覆盖。
func TestHostOf(t *testing.T) {
	cases := map[string]string{
		"https://im.example.com":      "im.example.com",
		"https://im.example.com:8443": "im.example.com",
		"https://im.example.com/sub/": "im.example.com",
		"http://192.168.1.10:8080":    "192.168.1.10",
		"":                            "",
		"not-a-url":                   "",
	}
	for in, want := range cases {
		if got := hostOf(in); got != want {
			t.Errorf("hostOf(%q) = %q, want %q", in, got, want)
		}
	}
}

func TestWebauthnOrigins(t *testing.T) {
	// 显式配置优先
	got := webauthnOrigins("https://im.example.com", "https://a.example.com,https://b.example.com")
	want := []string{"https://a.example.com", "https://b.example.com"}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("显式配置应优先: %v", got)
	}
	// 未显式配置时用站点地址（去尾斜杠）
	got = webauthnOrigins("https://im.example.com/", "")
	want = []string{"https://im.example.com"}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("应从站点地址推导: %v", got)
	}
	// 两者都空 = 功能关闭
	if got := webauthnOrigins("", ""); len(got) != 0 {
		t.Fatalf("都为空时不应有 origin: %v", got)
	}
}
