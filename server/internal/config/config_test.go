package config

import "testing"

func TestSplitList(t *testing.T) {
	got := splitList(" https://a.com , ,https://b.com,")
	want := []string{"https://a.com", "https://b.com"}
	if len(got) != len(want) {
		t.Fatalf("got %v", got)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("got %v, want %v", got, want)
		}
	}
	if splitList("") != nil {
		t.Fatal("空串应返回 nil")
	}
}

func TestOriginAllowed(t *testing.T) {
	// 未配置白名单：保持原有宽松行为（本地开发 / 私有化默认拓扑）
	open := &Config{}
	if !open.OriginAllowed("https://evil.example") {
		t.Fatal("未配置 IM_ALLOWED_ORIGINS 时应放行")
	}
	if !open.OriginAllowed("") {
		t.Fatal("原生客户端不带 Origin 应放行")
	}

	strict := &Config{AllowedOrigins: []string{"https://im.example.com"}}
	if !strict.OriginAllowed("https://im.example.com") {
		t.Fatal("白名单内应放行")
	}
	if !strict.OriginAllowed("") {
		t.Fatal("不带 Origin（desktop/android）应放行")
	}
	if strict.OriginAllowed("https://evil.example") {
		t.Fatal("白名单外必须拒绝")
	}
	// 大小写与结尾斜杠差异不应影响匹配
	if !strict.OriginAllowed("HTTPS://im.example.com/") {
		t.Fatal("应容忍大小写与尾斜杠")
	}
}

func TestCheckSecrets(t *testing.T) {
	// 开发模式下仍使用默认密钥 → 只告警不失败
	dev := Load()
	if err := dev.CheckSecrets(); err != nil {
		t.Fatalf("开发模式不应失败: %v", err)
	}
	// 生产模式下使用默认密钥 → 必须失败
	prod := Load()
	prod.RequireStrongSecrets = true
	if err := prod.CheckSecrets(); err == nil {
		t.Fatal("IM_REQUIRE_STRONG_SECRETS 下使用默认密钥必须报错")
	}
	// 覆盖密钥后通过
	safe := Load()
	safe.JWTSecret, safe.AdminToken, safe.LiveKitAPISecret = "a", "b", "c"
	safe.RequireStrongSecrets = true
	if err := safe.CheckSecrets(); err != nil {
		t.Fatalf("覆盖后应通过: %v", err)
	}
}

// TestOriginAllowedRequiresExactMatch 白名单必须整串匹配，
// 不能只比 host，避免 https 与 http 混用被放行
func TestOriginAllowedRequiresExactMatch(t *testing.T) {
	c := &Config{AllowedOrigins: []string{"https://im.example.com"}}
	if c.OriginAllowed("http://im.example.com") {
		t.Fatal("协议不同不应放行")
	}
}
