package store

import "testing"

func TestLikePattern(t *testing.T) {
	cases := map[string]string{
		"hello": "hello",
		"50%":   `50\%`,
		"a_b":   `a\_b`,
		`back\`: `back\\`,
		"%_%":   `\%\_\%`,
		"%%":    `\%\%`,
	}
	for in, want := range cases {
		if got := LikePattern(in); got != want {
			t.Errorf("LikePattern(%q) = %q, want %q", in, got, want)
		}
	}
	if got := LikeQuery("a%"); got != `%a\%%` {
		t.Errorf("LikeQuery = %q", got)
	}
}
