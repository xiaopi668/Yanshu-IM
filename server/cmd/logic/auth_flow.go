package main

// 四期：认证增强 —— 站点配置公开下发 / Turnstile / 邮箱验证码 / OIDC 登录

import (
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"net/http"
	"net/url"
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
	base := a.baseURL(r)
	for _, p := range c.OIDCProviders {
		oidcList = append(oidcList, pubOIDC{
			Name: p.Name,
			// redirect_uri 固定指向本服务的 OIDC 回调（而非前端路由），
			// 由服务端完成 code 换 token 后再带回跳地址
			AuthorizeURL: fmt.Sprintf("%s/v1/oidc/%s/authorize?redirect_uri=%s",
				base, urlEscape(p.Name), urlEscape(oidcCallbackURI(base, p.Name))),
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

// oidcCallbackURI 本站固定的 OIDC 回调地址
func oidcCallbackURI(base, name string) string {
	return base + "/v1/oidc/" + name + "/callback"
}

// baseURL 站点对外地址。优先取 IM_PUBLIC_BASE_URL（权威值，不信任任何请求头）；
// 否则由请求推导，且仅在 IM_TRUST_PROXY=true 时才信任 X-Forwarded-Host。
func (a *apiv1) baseURL(r *http.Request) string {
	if a.cfg.PublicBaseURL != "" {
		return strings.TrimRight(a.cfg.PublicBaseURL, "/")
	}
	if h := r.Header.Get("X-Forwarded-Host"); h != "" && a.cfg.TrustProxy {
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

// rateLimit 按客户端 IP 的固定窗口限流，用于登录/注册/发验证码等可被暴力尝试或滥用的入口。
// limit<=0 关闭；Redis 故障时放行（fail-open），不能因为缓存挂了就全员登录失败。
// loopback（本机/单测/E2E）不计入：攻击者能打到 127.0.0.1 说明已经在主机上，
// 而 docker compose 里经网桥进来的客户端也不在 loopback，不受影响。
func (a *apiv1) rateLimit(r *http.Request, bucket string, limit int64, window time.Duration) error {
	if limit <= 0 {
		return nil
	}
	ip := clientIP(r, a.cfg.TrustProxy)
	if parsed := net.ParseIP(ip); parsed != nil && parsed.IsLoopback() {
		return nil
	}
	key := "im:rl:" + bucket + ":" + ip
	n, err := a.rdb.Incr(r.Context(), key).Result()
	if err != nil {
		return nil
	}
	if n == 1 {
		_ = a.rdb.Expire(r.Context(), key, window).Err()
	}
	if n > limit {
		return errors.New("操作过于频繁，请稍后再试")
	}
	return nil
}

func urlEscape(s string) string { return strings.ReplaceAll(s, "&", "%26") }

// clientIP 取客户端 IP。仅在 IM_TRUST_PROXY=true 时信任 X-Forwarded-For，
// 否则一律取直连地址，防止伪造该头绕过基于 IP 的限制。
func clientIP(r *http.Request, trustProxy bool) string {
	if v := r.Header.Get("X-Forwarded-For"); v != "" && trustProxy {
		return strings.TrimSpace(strings.Split(v, ",")[0])
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr
	}
	return host
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
	// 验证码通道是邮件轰炸的放大器，限流比登录更紧
	limits := a.cfg.AuthRateLimit / 12
	if a.cfg.AuthRateLimit > 0 && limits < 1 {
		limits = 1
	}
	if err := a.rateLimit(r, "emailcode", limits, 10*time.Minute); err != nil {
		fail(w, 429, err)
		return
	}
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
	if err := a.checkTurnstile(c, req.TurnstileToken, clientIP(r, a.cfg.TrustProxy)); err != nil {
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

// oidcProvider 按名称查找已配置的身份提供商
func oidcProvider(c siteconf.Conf, name string) *siteconf.OIDCProvider {
	for i := range c.OIDCProviders {
		if c.OIDCProviders[i].Name == name {
			return &c.OIDCProviders[i]
		}
	}
	return nil
}

// checkRedirectURI 校验 OIDC 回跳地址。
// 只接受「本站固定的回调地址」或服务商显式配置的 RedirectURIs，
// 防止攻击者构造 redirect_uri=https://evil.com 把签发的 JWT 送到自己域名（开放重定向 → 账号接管）。
func checkRedirectURI(ours, got string, p *siteconf.OIDCProvider) error {
	if got == "" {
		return errors.New("缺少 redirect_uri")
	}
	u, err := url.Parse(got)
	if err != nil || u.Host == "" || (u.Scheme != "http" && u.Scheme != "https") {
		return errors.New("redirect_uri 需为 http(s) 绝对地址")
	}
	if strings.EqualFold(strings.TrimRight(got, "/"), strings.TrimRight(ours, "/")) {
		return nil
	}
	for _, allow := range p.RedirectURIs {
		if strings.EqualFold(strings.TrimRight(allow, "/"), strings.TrimRight(got, "/")) {
			return nil
		}
	}
	return errors.New("redirect_uri 不在允许列表内")
}

// checkReturnTo 校验登录成功后浏览器的最终去向，必须与本站同源
func checkReturnTo(base, got string) (string, error) {
	if got == "" {
		return base + "/", nil
	}
	u, err := url.Parse(got)
	if err != nil || u.Host == "" || (u.Scheme != "http" && u.Scheme != "https") {
		return "", errors.New("return_to 需为 http(s) 绝对地址")
	}
	b, err := url.Parse(base)
	if err != nil || !strings.EqualFold(u.Host, b.Host) {
		return "", errors.New("return_to 必须与本站同源")
	}
	u.Fragment = "" // token 走 fragment，避免与已有 fragment 冲突
	return u.String(), nil
}

// oidcAuthorize 302 到身份提供商授权页
func (a *apiv1) oidcAuthorize(w http.ResponseWriter, r *http.Request) {
	name := r.PathValue("name")
	base := a.baseURL(r)
	redirectURI := r.URL.Query().Get("redirect_uri")
	if redirectURI == "" {
		redirectURI = oidcCallbackURI(base, name)
	}
	returnTo := r.URL.Query().Get("return_to")

	c := a.siteConf()
	provider := oidcProvider(c, name)
	if provider == nil {
		fail(w, 404, errors.New("OIDC provider 不存在"))
		return
	}
	if err := checkRedirectURI(oidcCallbackURI(base, name), redirectURI, provider); err != nil {
		fail(w, 400, err)
		return
	}
	returnURI, err := checkReturnTo(base, returnTo)
	if err != nil {
		fail(w, 400, err)
		return
	}
	d, err := siteconf.Discover(provider.Issuer)
	if err != nil {
		fail(w, 502, err)
		return
	}
	state := randHex(16)
	// state → 5 分钟内有效，绑定 redirect_uri / 回跳地址 / provider
	binding := strings.Join([]string{name, redirectURI, returnURI}, "|")
	if err := a.rdb.Set(r.Context(), "im:oidc:state:"+state, binding, 5*time.Minute).Err(); err != nil {
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
	parts := strings.SplitN(binding, "|", 3)
	if len(parts) < 2 {
		fail(w, 400, errors.New("state 无效"))
		return
	}
	name, redirectURI := parts[0], parts[1]
	returnTo := ""
	if len(parts) == 3 {
		returnTo = parts[2]
	}

	base := a.baseURL(r)
	c := a.siteConf()
	provider := oidcProvider(c, name)
	if provider == nil {
		fail(w, 404, errors.New("OIDC provider 不存在"))
		return
	}
	// 二次校验：从发起授权到回调期间配置可能已变更，同样不能放宽
	if err := checkRedirectURI(oidcCallbackURI(base, name), redirectURI, provider); err != nil {
		fail(w, 400, err)
		return
	}
	returnURI, err := checkReturnTo(base, returnTo)
	if err != nil {
		fail(w, 400, err)
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
	// 回跳到发起登录时的页面，token 放 fragment（同源校验已在 authorize/callback 两处做过）
	http.Redirect(w, r, returnURI+"#token="+tok+"&uid="+uid, http.StatusFound)
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
