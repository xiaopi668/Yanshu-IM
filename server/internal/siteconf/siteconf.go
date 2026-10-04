// Package siteconf 站点配置（登录方式开关）+ Turnstile / 邮箱验证码 / OIDC。
package siteconf

import (
	"context"
	"crypto/rand"
	"database/sql"
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/mail"
	"net/url"
	"strings"
	"time"

	"github.com/redis/go-redis/v9"
	"net/smtp"
)

// Conf 站点配置（管理后台可改；公开部分经 /v1/site-config 下发）
type Conf struct {
	RegistrationEnabled bool           `json:"registration_enabled"`
	TurnstileEnabled    bool           `json:"turnstile_enabled"`
	TurnstileSiteKey    string         `json:"turnstile_site_key"`
	TurnstileSecretKey  string         `json:"turnstile_secret_key"`
	EmailCodeEnabled    bool           `json:"email_code_enabled"`
	SMTPHost            string         `json:"smtp_host"`
	SMTPPort            int            `json:"smtp_port"`
	SMTPUser            string         `json:"smtp_user"`
	SMTPPass            string         `json:"smtp_pass"`
	SMTPFrom            string         `json:"smtp_from"`
	OIDCProviders       []OIDCProvider `json:"oidc_providers"`
}

// OIDCProvider 一个身份提供商配置
type OIDCProvider struct {
	Name         string `json:"name"`   // 显示名 & 路由标识（如 google / github / company-sso）
	Issuer       string `json:"issuer"` // 如 https://accounts.google.com
	ClientID     string `json:"client_id"`
	ClientSecret string `json:"client_secret"`
	// RedirectURIs 允许的回跳地址白名单（可选）。
	// 未配置时只接受本站固定的 /v1/oidc/{name}/callback，防止开放重定向盗取 JWT。
	RedirectURIs []string `json:"redirect_uris,omitempty"`
}

// ---------- 存取 ----------

func Load(db *sql.DB) (Conf, error) {
	c := Conf{RegistrationEnabled: true, SMTPPort: 587}
	rows, err := db.Query(`SELECT k, v FROM site_config`)
	if err != nil {
		return c, err
	}
	defer rows.Close()
	kv := map[string]string{}
	for rows.Next() {
		var k, v string
		if err := rows.Scan(&k, &v); err == nil {
			kv[k] = v
		}
	}
	if v, ok := kv["registration_enabled"]; ok {
		c.RegistrationEnabled = v == "1"
	}
	if v, ok := kv["turnstile_enabled"]; ok {
		c.TurnstileEnabled = v == "1"
	}
	c.TurnstileSiteKey = kv["turnstile_site_key"]
	c.TurnstileSecretKey = kv["turnstile_secret_key"]
	if v, ok := kv["email_code_enabled"]; ok {
		c.EmailCodeEnabled = v == "1"
	}
	c.SMTPHost = kv["smtp_host"]
	if v, ok := kv["smtp_port"]; ok {
		fmt.Sscanf(v, "%d", &c.SMTPPort)
	}
	c.SMTPUser = kv["smtp_user"]
	c.SMTPPass = kv["smtp_pass"]
	c.SMTPFrom = kv["smtp_from"]
	if v, ok := kv["oidc_json"]; ok {
		_ = json.Unmarshal([]byte(v), &c.OIDCProviders)
	}
	return c, nil
}

func Save(db *sql.DB, c Conf) error {
	oidcJSON, _ := json.Marshal(c.OIDCProviders)
	kv := map[string]string{
		"registration_enabled": boolStr(c.RegistrationEnabled),
		"turnstile_enabled":    boolStr(c.TurnstileEnabled),
		"turnstile_site_key":   c.TurnstileSiteKey,
		"turnstile_secret_key": c.TurnstileSecretKey,
		"email_code_enabled":   boolStr(c.EmailCodeEnabled),
		"smtp_host":            c.SMTPHost,
		"smtp_port":            fmt.Sprint(c.SMTPPort),
		"smtp_user":            c.SMTPUser,
		"smtp_pass":            c.SMTPPass,
		"smtp_from":            c.SMTPFrom,
		"oidc_json":            string(oidcJSON),
	}
	for k, v := range kv {
		if _, err := db.Exec(
			`INSERT INTO site_config(k, v) VALUES(?,?) ON DUPLICATE KEY UPDATE v=?`, k, v, v); err != nil {
			return err
		}
	}
	return nil
}

func boolStr(b bool) string {
	if b {
		return "1"
	}
	return "0"
}

// ---------- Turnstile ----------

var siteverifyURL = "https://challenges.cloudflare.com/turnstile/v0/siteverify"

// VerifyTurnstile 服务端校验 Cloudflare Turnstile token
func VerifyTurnstile(secret, token, ip string) error {
	if strings.TrimSpace(token) == "" {
		return errors.New("请完成人机验证")
	}
	form := url.Values{
		"secret":   {secret},
		"response": {token},
		"remoteip": {ip},
	}
	client := &http.Client{Timeout: 10 * time.Second}
	resp, err := client.Post(siteverifyURL, "application/x-www-form-urlencoded", strings.NewReader(form.Encode()))
	if err != nil {
		return fmt.Errorf("人机验证服务不可达: %w", err)
	}
	defer resp.Body.Close()
	var result struct {
		Success bool `json:"success"`
	}
	body, _ := io.ReadAll(resp.Body)
	if err := json.Unmarshal(body, &result); err != nil {
		return fmt.Errorf("人机验证响应解析失败: %w", err)
	}
	if !result.Success {
		return errors.New("人机验证未通过")
	}
	return nil
}

// ---------- 邮箱验证码 ----------

const emailCodeTTL = 5 * time.Minute

// SendEmailCode 生成并发送验证码；返回是否成功
func SendEmailCode(rdb *redis.Client, c Conf, email string) error {
	if _, err := mail.ParseAddress(email); err != nil {
		return errors.New("邮箱格式不正确")
	}
	ctx := context.Background()
	// 频率限制：同邮箱 60s 内一次
	ok, err := rdb.SetNX(ctx, "im:ecode:rl:"+email, 1, 60*time.Second).Result()
	if err == nil && !ok {
		return errors.New("发送太频繁，请稍后再试")
	}
	// 验证码必须用密码学随机数，时间戳可被预测
	var rnd [4]byte
	if _, err := rand.Read(rnd[:]); err != nil {
		return err
	}
	code := fmt.Sprintf("%06d", binary.BigEndian.Uint32(rnd[:])%1000000)
	if err := rdb.Set(ctx, "im:ecode:"+email, code, emailCodeTTL).Err(); err != nil {
		return err
	}
	return sendMail(c, email, "雁书验证码",
		fmt.Sprintf("你的验证码是 %s，%d 分钟内有效。", code, int(emailCodeTTL.Minutes())))
}

// CheckEmailCode 校验并消费验证码
func CheckEmailCode(rdb *redis.Client, email, code string) error {
	ctx := context.Background()
	saved, err := rdb.Get(ctx, "im:ecode:"+email).Result()
	if err == redis.Nil {
		return errors.New("验证码已过期，请重新发送")
	}
	if err != nil {
		return err
	}
	if saved != code {
		return errors.New("验证码不正确")
	}
	_ = rdb.Del(ctx, "im:ecode:"+email).Err()
	return nil
}

// SendTestMail 用当前 SMTP 配置实发一封测试邮件。
//
// 给管理后台用：配置完 SMTP 后立刻验证链路是否真的通（凭据对不对、端口对不对、
// 发件人是否被服务商接受）。刻意不复用验证码逻辑 —— 不写 Redis、不做频率限制、
// 不生成验证码，纯粹只验证「能不能发出去」。
func SendTestMail(c Conf, to string) error {
	to = strings.TrimSpace(to)
	if _, err := mail.ParseAddress(to); err != nil {
		return errors.New("收件邮箱格式不正确")
	}
	if c.SMTPHost == "" {
		return errors.New("SMTP 未配置：请先填写服务器地址并保存")
	}
	from := c.SMTPFrom
	if from == "" {
		from = c.SMTPUser
	}
	body := strings.Join([]string{
		"这是一封来自雁书管理后台的测试邮件。",
		"",
		"收到它说明当前 SMTP 配置可用。",
		"",
		"服务器：" + fmt.Sprintf("%s:%d", c.SMTPHost, c.SMTPPort),
		"发件人：" + from,
		"时间：" + time.Now().Format("2006-01-02 15:04:05"),
		"",
	}, "\r\n")
	return sendMail(c, to, "雁书 SMTP 测试邮件", body)
}

// sendMail 通过 SMTP 发送（StartTLS/明文，MVP 不做 465 隐式 TLS）
func sendMail(c Conf, to, subject, body string) error {
	if c.SMTPHost == "" {
		return errors.New("SMTP 未配置")
	}
	addr := fmt.Sprintf("%s:%d", c.SMTPHost, c.SMTPPort)
	from := c.SMTPFrom
	if from == "" {
		from = c.SMTPUser
	}
	msg := strings.Join([]string{
		fmt.Sprintf("From: %s", from),
		fmt.Sprintf("To: %s", to),
		fmt.Sprintf("Subject: %s", subject),
		"MIME-Version: 1.0",
		"Content-Type: text/plain; charset=UTF-8",
		"",
		body,
	}, "\r\n")
	var client MailClient = mailClient
	return client.Send(addr, smtpAuth(c.SMTPUser, c.SMTPPass, c.SMTPHost), from, []string{to}, []byte(msg))
}

// mailClient 可被测试替换
var mailClient MailClient = &realMail{}

type MailClient interface {
	Send(addr string, auth smtp.Auth, from string, to []string, msg []byte) error
}

type realMail struct{}

func (r *realMail) Send(addr string, auth smtp.Auth, from string, to []string, msg []byte) error {
	return smtpSendMail(addr, auth, from, to, msg)
}
