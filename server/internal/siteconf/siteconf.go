// Package siteconf 站点配置（登录方式开关）+ Turnstile / 邮箱验证码 / OIDC。
package siteconf

import (
	"context"
	"database/sql"
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
	code := fmt.Sprintf("%06d", time.Now().UnixNano()%1000000)
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
	var client MailClient = &realMail{}
	return client.Send(addr, smtpAuth(c.SMTPUser, c.SMTPPass, c.SMTPHost), from, []string{to}, []byte(msg))
}

type MailClient interface {
	Send(addr string, auth smtp.Auth, from string, to []string, msg []byte) error
}

type realMail struct{}

func (r *realMail) Send(addr string, auth smtp.Auth, from string, to []string, msg []byte) error {
	return smtpSendMail(addr, auth, from, to, msg)
}
