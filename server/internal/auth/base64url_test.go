package auth

import "testing"

func TestBase64URLRoundTrip(t *testing.T) {
	for _, raw := range [][]byte{{0x00}, {0xff, 0x00, 0x7f}, []byte("hello-world_123")} {
		enc := Base64URL(raw)
		if len(enc) > 0 && (enc[len(enc)-1] == '=') {
			t.Fatalf("不应带填充: %s", enc)
		}
		dec, err := Base64URLDecode(enc)
		if err != nil {
			t.Fatalf("解码失败 %s: %v", enc, err)
		}
		if string(dec) != string(raw) {
			t.Fatalf("round trip 不一致: %v != %v", dec, raw)
		}
	}
	if _, err := Base64URLDecode(""); err == nil {
		t.Fatal("空串应报错")
	}
}
