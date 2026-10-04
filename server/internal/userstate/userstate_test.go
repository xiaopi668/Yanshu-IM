package userstate

import "testing"

// 缓存里的状态串是 "disabled:tokenVersion"。
// 解析失败必须返回 ok=false（上层会落库重读），绝不能把脏值当成"未封禁、版本 0"，
// 否则一个损坏的缓存项就等于把封禁/撤销静默放开了。
func TestParseCacheValue(t *testing.T) {
	cases := []struct {
		raw string
		ok  bool
		dis bool
		ver int64
	}{
		{"0:0", true, false, 0},
		{"1:3", true, true, 3},
		{"0:9007199254740993", true, false, 9007199254740993},
		{"", false, false, 0},
		{"1", false, false, 0},
		{"x:1", false, false, 0},
		{"1:y", false, false, 0},
	}
	for _, c := range cases {
		st, ok := parse(c.raw)
		if ok != c.ok {
			t.Errorf("parse(%q) ok=%v，期望 %v", c.raw, ok, c.ok)
			continue
		}
		if ok && (st.Disabled != c.dis || st.TokenVersion != c.ver) {
			t.Errorf("parse(%q) = %+v，期望 disabled=%v ver=%d", c.raw, st, c.dis, c.ver)
		}
	}
}
