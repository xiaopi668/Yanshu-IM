package main

// 四期：认证增强 —— 站点配置公开下发 / Turnstile / 邮箱验证码 / OIDC 登录

import (
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"time"

	"im/internal/auth"
	"im/internal/pb"
	"im/internal/siteconf"
)

// siteConf 当前站点配置（每次读库，配置变更即时生效）
func (a *apiv1) siteConf() siteconf.Conf {
	c, err := siteconf.Load(a.db.DB)
	if err != nil {
		return siteconf.Conf{RegistrationEnabled: true}
	}
	return c
}

// publicSiteConfig 公开配置（不含任何 secret）
func (a *apiv1) publicSiteConfig(w http.ResponseWriter, r *http.Request) {
	c := a.siteConf()
	type pubOIDC struct {
		Name         string `json:"name"`
		AuthorizeURL string `json:"authorize_url"`
	}
	oidcList := []pubOIDC{}
	base := publicBaseURL(r)
	for _, p := range c.OIDCProviders {
		oidcList = append(oidcList, pubOIDC{
			Name:         p.Name,
			AuthorizeURL: fmt.Sprintf("%s/v1/oidc/%s/authorize?redirect_uri=%s",
				base, urlEscape(p.Name), urlEscape(base+"/oidc-callback/"+p.Name)),
		})
	}
	writeJSON(w, 200, map[string]any{
		"registration_enabled": c.RegistrationEnabled,
		"turnstile_enabled":    c.TurnstileEnabled,
		"turnstile_site_key":   c.TurnstileSiteKey,
		"email_code_enabled":   c.EmailCodeEnabled,
		"oidc_providers":       oidcList,
	})
}

func publicBaseURL(r *http.Request) string {
	if h := r.Header.Get("X-Forwarded-Host"); h != "" {
		scheme := r.Header.Get("X-Forwarded-Proto")
		if scheme == "" {
			scheme = "https"
		}
		return scheme + "://" + h
	}
	scheme := "http"
	if r.TLS != nil {
		scheme = "https"
	}
	return scheme + "://" + r.Host
}

func urlEscape(s string) string { return strings.ReplaceAll(s, "&", "%26") }

func clientIP(r *http.Request) string {
	if v := r.Header.Get("X-Forwarded-For"); v != "" {
		return strings.TrimSpace(strings.Split(v, ",")[0])
	}
	return strings.Split(r.RemoteAddr, ":")[0]
}

// checkTurnstile 站点开启 Turnstile 时校验 token
func (a *apiv1) checkTurnstile(c siteconf.Conf, token, ip string) error {
	if !c.TurnstileEnabled {
		return nil
	}
	return siteconf.VerifyTurnstile(c.TurnstileSecretKey, token, ip)
}

// sendEmailCode 发送邮箱验证码
func (a *apiv1) sendEmailCode(w http.ResponseWriter, r *http.Request) {
	c := a.siteConf()
	if !c.EmailCodeEnabled {
		fail(w, 403, errors.New("邮箱验证码未启用"))
		return
	}
	var req struct {
		Email          string `json:"email"`
		TurnstileToken string `json:"turnstile_token"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		fail(w, 400, err)
		return
	}
	if err := a.checkTurnstile(c, req.TurnstileToken, clientIP(r)); err != nil {
		fail(w, 403, err)
		return
	}
	if err := siteconf.SendEmailCode(a.rdb, c, strings.ToLower(strings.TrimSpace(req.Email))); err != nil {
		fail(w, 400, err)
		return
	}
	writeJSON(w, 200, map[string]bool{"ok": true})
}

var _ = pb.MsgType_MSG_SYSTEM

// ---------- OIDC ----------

// oidcAuthorize 302 到身份提供商授权页
func (a *apiv1) oidcAuthorize(w http.ResponseWriter, r *http.Request) {
	name := r.PathValue("name")
	redirectURI := r.URL.Query().Get("redirect_uri")
	if !strings.HasPrefix(redirectURI, "http://") && !strings.HasPrefix(redirectURI, "https://") {
		fail(w, 400, errors.New("redirect_uri 需为 http(s)"))
		return
	}
	c := a.siteConf()
	var provider *siteconf.OIDCProvider
	for _, p := range c.OIDCProviders {
		if p.Name == name {
			provider = &p
			break
		}
	}
	if provider == nil {
		fail(w, 404, errors.New("OIDC provider 不存在"))
		return
	}
	d, err := siteconf.Discover(provider.Issuer)
	if err != nil {
		fail(w, 502, err)
		return
	}
	state := randHex(16)
	// state → 5 分钟内有效，绑定 redirect_uri 与 provider
	if err := a.rdb.Set(r.Context(), "im:oidc:state:"+state,
		name+"|"+redirectURI, 5*time.Minute).Err(); err != nil {
		fail(w, 500, err)
		return
	}
	target := fmt.Sprintf("%s?client_id=%s&redirect_uri=%s&response_type=code&scope=%s&state=%s",
		d.AuthorizeURL, urlQ(provider.ClientID), urlQ(redirectURI), urlQ("openid profile email"), urlQ(state))
	http.Redirect(w, r, target, http.StatusFound)
}

func urlQ(s string) string { return strings.ReplaceAll(strings.ReplaceAll(s, " ", "%20"), "&", "%26") }

func randHex(n int) string {
	b := make([]byte, n)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

// oidcCallback 授权码回调：换 token → userinfo → 关联/创建用户 → 签发 JWT 回跳
func (a *apiv1) oidcCallback(w http.ResponseWriter, r *http.Request) {
	state := r.URL.Query().Get("state")
	code := r.URL.Query().Get("code")
	binding, err := a.rdb.Get(r.Context(), "im:oidc:state:"+state).Result()
	if err != nil {
		fail(w, 400, errors.New("state 无效或已过期"))
		return
	}
	_ = a.rdb.Del(r.Context(), "im:oidc:state:"+state).Err()
	parts := strings.SplitN(binding, "|", 2)
	if len(parts) != 2 {
		fail(w, 400, errors.New("state 无效"))
		return
	}
	name, redirectURI := parts[0], parts[1]

	c := a.siteConf()
	var provider *siteconf.OIDCProvider
	for _, p := range c.OIDCProviders {
		if p.Name == name {
			provider = &p
			break
		}
	}
	if provider == nil {
		fail(w, 404, errors.New("OIDC provider 不存在"))
		return
	}
	d, err := siteconf.Discover(provider.Issuer)
	if err != nil {
		fail(w, 502, err)
		return
	}
	accessToken, err := siteconf.TokenExchange(d, *provider, code, redirectURI)
	if err != nil {
		fail(w, 502, err)
		return
	}
	sub, display, email, err := siteconf.FetchUserInfo(d, accessToken)
	if err != nil {
		fail(w, 502, err)
		return
	}

	// 已关联 → 直接登录
	var uid string
	err = a.db.QueryRow(`SELECT uid FROM oidc_user WHERE provider=? AND sub=?`, name, sub).Scan(&uid)
	if errors.Is(err, sql.ErrNoRows) {
		// 首次登录：创建账号并关联
		uid = a.msg.ID.Next()
		username := "oidc_" + name + "_" + shortHash(sub)
		nick := display
		if nick == "" {
			nick = username
		}
		yid := username
		now := time.Now().UnixMilli()
		hash, _ := auth.HashPassword(randHex(16)) // 随机密码（OIDC 登录不使用）
		if _, err := a.db.Exec(
			`INSERT INTO user(uid, username, password_hash, nickname, yid, yid_changed, email, created_at) VALUES(?,?,?,?,?,?,?,?)`,
			uid, username, hash, nick, yid, 1, nullIfEmpty(email), now); err != nil {
			fail(w, 500, err)
			return
		}
		_, _ = a.db.Exec(
			`INSERT INTO oidc_user(provider, sub, uid, email, linked_at) VALUES(?,?,?,?,?)`,
			name, sub, uid, email, now)
	} else if err != nil {
		fail(w, 500, err)
		return
	}

	tok, _ := auth.MakeToken(a.cfg.JWTSecret, uid, "oidc")
	// 回跳客户端页面，token 放 fragment
	http.Redirect(w, r, redirectURI+"#token="+tok+"&uid="+uid, http.StatusFound)
}

func nullIfEmpty(s string) any {
	if s == "" {
		return nil
	}
	return s
}

func shortHash(s string) string {
	sum := 0
	for _, c := range s {
		sum = sum*31 + int(c)
	}
	return fmt.Sprintf("%x", sum)
}

var _ = sql.ErrNoRows
