package auth

// TOTP（RFC 6238）最小实现：HMAC-SHA1 + 30 秒步长 + 6 位码。
//
// 为什么不用第三方库：本项目已经在手写 protobuf 编解码，TOTP 的核心也就
// 「HMAC + 动态截断 + base32」三步，用 RFC 附录 B 的官方测试向量钉住即可，
// 少一个依赖就少一处供应链与升级负担。

import (
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha1"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base32"
	"encoding/binary"
	"encoding/hex"
	"fmt"
	"net/url"
	"strings"
	"time"
)

// StepSeconds 是 TOTP 的标准步长。改动会让所有已绑定的验证器失效，别动。
const StepSeconds = 30

// Digits 验证码位数。6 位是 Google Authenticator / 1Password 等的默认值。
const Digits = 6

var b32 = base32.StdEncoding.WithPadding(base32.NoPadding)

// GenerateSecret 生成 160 位随机密钥（RFC 4226 建议长度），base32 无填充编码。
func GenerateSecret() (string, error) {
	raw := make([]byte, 20)
	if _, err := rand.Read(raw); err != nil {
		return "", err
	}
	return b32.EncodeToString(raw), nil
}

// hotp 计算 RFC 4226 的动态截断值
func hotp(key []byte, counter uint64) uint32 {
	var buf [8]byte
	binary.BigEndian.PutUint64(buf[:], counter)
	mac := hmac.New(sha1.New, key)
	mac.Write(buf[:])
	sum := mac.Sum(nil)
	// 动态截断：取最后一个字节的低 4 位作为偏移
	off := sum[len(sum)-1] & 0x0f
	code := (uint32(sum[off]&0x7f) << 24) |
		(uint32(sum[off+1]) << 16) |
		(uint32(sum[off+2]) << 8) |
		uint32(sum[off+3])
	return code % 1_000_000
}

// Code 计算某一时刻的 6 位验证码
func Code(secret string, t time.Time) (string, error) {
	key, err := decodeSecret(secret)
	if err != nil {
		return "", err
	}
	return fmt.Sprintf("%06d", hotp(key, uint64(t.Unix()/StepSeconds))), nil
}

// Verify 校验验证码，允许 ±skew 个步长的时钟偏差。
//
// 比对用 subtle.ConstantTimeCompare：字符串 == 在理论上可被计时侧信道区分，
// 而这里每次请求都带一个 6 位数字，暴力破解面本来就小，不该再额外漏时间信息。
func Verify(secret, code string, t time.Time, skew int) bool {
	code = strings.TrimSpace(code)
	if len(code) != Digits {
		return false
	}
	if skew < 0 {
		skew = 0
	}
	step := t.Unix() / StepSeconds
	for d := -int64(skew); d <= int64(skew); d++ {
		at := time.Unix((step+d)*StepSeconds, 0)
		want, err := Code(secret, at)
		if err != nil {
			return false
		}
		if subtle.ConstantTimeCompare([]byte(want), []byte(code)) == 1 {
			return true
		}
	}
	return false
}

// OtpauthURL 生成验证器 App 扫码用的 otpauth:// 地址
func OtpauthURL(issuer, account, secret string) string {
	label := url.PathEscape(issuer + ":" + account)
	q := url.Values{}
	q.Set("secret", secret)
	q.Set("issuer", issuer)
	q.Set("algorithm", "SHA1")
	q.Set("digits", fmt.Sprint(Digits))
	q.Set("period", fmt.Sprint(StepSeconds))
	return "otpauth://totp/" + label + "?" + q.Encode()
}

// RecoveryCodes 生成 n 个一次性恢复码（形如 a1b2-c3d4）。
//
// 恢复码是「手机丢了」时的唯一出路：没有它，用户只能找管理员清库。
func RecoveryCodes(n int) ([]string, error) {
	out := make([]string, 0, n)
	for i := 0; i < n; i++ {
		buf := make([]byte, 4)
		if _, err := rand.Read(buf); err != nil {
			return nil, err
		}
		h := hex.EncodeToString(buf)
		out = append(out, h[0:4]+"-"+h[4:8])
	}
	return out, nil
}

// HashRecovery 恢复码入库前先做 SHA-256：即使库被拖走，也不能直接拿来登录。
//
// 这里不用 bcrypt：恢复码本身是 32 位随机串（4 字节），没有低熵口令的问题，
// 而登录路径上要对整张表逐个比对，bcrypt 的慢反而会被利用来打 DoS。
func HashRecovery(code string) string {
	norm := strings.ToLower(strings.ReplaceAll(strings.TrimSpace(code), "-", ""))
	sum := sha256.Sum256([]byte(norm))
	return hex.EncodeToString(sum[:])
}

func decodeSecret(secret string) ([]byte, error) {
	s := strings.ToUpper(strings.ReplaceAll(strings.TrimSpace(secret), " ", ""))
	return b32.DecodeString(s)
}
