package main

// 四期：认证增强 —— 站点配置公开下发 / Turnstile / 邮箱验证码 / OIDC 登录

import (
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strings"
	"time"

	"github.com/redis/go-redis/v9"

	"im/internal/auth"
	"im/internal/pb"
	"im/internal/siteconf"
	"im/internal/userstate"
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
	ip, fromProxy := clientIPTrusted(r, a.cfg.TrustProxy)
	// loopback 豁免只给「直连本机」：本机开发与单测不必自我限流。
	// 地址来自 X-Forwarded-For 时绝不豁免 —— 否则任何客户端加一个
	// "X-Forwarded-For: 127.0.0.1" 就能把登录/注册/发码的限流整体关掉。
	if !fromProxy {
		if parsed := net.ParseIP(ip); parsed != nil && parsed.IsLoopback() {
			return nil
		}
	}
	key := "im:rl:" + bucket + ":" + ip
	// INCR 与 EXPIRE 必须原子：分两步做的话，进程若崩在中间会给该 IP 留下一个
	// 永不过期的计数键，这个 IP 之后就被永久限流（也能被用来恶意锁死别人）。
	n, err := rlScript.Run(r.Context(), a.rdb, []string{key}, window.Milliseconds()).Int64()
	if err != nil {
		// Redis 故障时放行：不能因为缓存挂了就让所有人登录失败
		return nil
	}
	if n > limit {
		return errors.New("操作过于频繁，请稍后再试")
	}
	return nil
}

// rlScript 固定窗口计数：INCR 与 PEXPIRE 原子完成；
// 另外对「已存在但没有 TTL」的键做自愈，避免历史遗留键永久锁死某个 IP。
var rlScript = redis.NewScript(`
local n = redis.call('INCR', KEYS[1])
if n == 1 or redis.call('PTTL', KEYS[1]) < 0 then
  redis.call('PEXPIRE', KEYS[1], ARGV[1])
end
return n
`)

func urlEscape(s string) string { return strings.ReplaceAll(s, "&", "%26") }

// clientIP 取客户端 IP。仅在 IM_TRUST_PROXY=true 时信任 X-Forwarded-For，
// 否则一律取直连地址，防止伪造该头绕过基于 IP 的限制。
func clientIP(r *http.Request, trustProxy bool) string {
	ip, _ := clientIPTrusted(r, trustProxy)
	return ip
}

// clientIPTrusted 取客户端 IP，并说明该值是否来自代理头。
// 取 X-Forwarded-For 的**最后一个**元素：直连的真实代理会把对端地址追加在末尾，
// 客户端自己伪造的值只可能出现在前面；取第一个等于让攻击者随便指定自己的 IP。
// 前提是「只信任一层自己的反代」，多层代理需改为按可信网段从右往左取。
func clientIPTrusted(r *http.Request, trustProxy bool) (string, bool) {
	if trustProxy {
		if v := r.Header.Get("X-Forwarded-For"); v != "" {
			parts := strings.Split(v, ",")
			if ip := strings.TrimSpace(parts[len(parts)-1]); ip != "" {
				return ip, true
			}
		}
	}
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return r.RemoteAddr, false
	}
	return host, false
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

// checkReturnTo 校验登录成功后浏览器的最终去向。
//
// 默认只允许与本站同源；此外可用 IM_OIDC_RETURN_ALLOWLIST 显式放行
// 「本地回环监听器」与「客户端自定义 scheme」，让令牌自动交回客户端，
// 用户不必再从浏览器里复制粘贴（fragment/query 的选择见 returnUsesFragment）。
func checkReturnTo(base, got string, allow []string) (string, error) {
	if got == "" {
		// 缺省落在本站的令牌展示页（首页 / 是 404，token 在 fragment 里没人展示）
		return base + "/oidc-done", nil
	}
	u, err := url.Parse(got)
	if err != nil || u.Scheme == "" {
		return "", errors.New("return_to 非法")
	}
	if (u.Scheme == "http" || u.Scheme == "https") && u.Host == "" {
		return "", errors.New("return_to 需为 http(s) 绝对地址")
	}
	if !sameOrigin(base, got) && !returnAllowed(got, allow) {
		return "", errors.New("return_to 必须与本站同源，或在 IM_OIDC_RETURN_ALLOWLIST 白名单内")
	}
	u.Fragment = "" // 不复用已有 fragment，避免冲突
	return u.String(), nil
}

// sameOrigin 同源判断：scheme 也必须一致，https 站点不能把令牌降级到 http
func sameOrigin(base, got string) bool {
	b, err := url.Parse(base)
	if err != nil {
		return false
	}
	u, err := url.Parse(got)
	if err != nil {
		return false
	}
	return strings.EqualFold(u.Scheme, b.Scheme) && strings.EqualFold(u.Host, b.Host)
}

// returnAllowed 白名单前缀匹配，且必须止于「边界」字符：
// 否则 http://127.0.0.1 会把 http://127.0.0.1.evil.com 一起放行 —— 那就是开放重定向。
func returnAllowed(got string, allow []string) bool {
	g := strings.ToLower(got)
	for _, p := range allow {
		p = strings.ToLower(strings.TrimSpace(p))
		if p == "" || !strings.HasPrefix(g, p) {
			continue
		}
		if len(g) == len(p) {
			return true
		}
		switch g[len(p)] {
		case '/', '?', '#', ':':
			return true
		}
	}
	return false
}

// returnUsesFragment 决定令牌放 fragment 还是 query：
//   - 同源页面：fragment（不进服务端日志与 Referer，页面用 JS 读）
//   - 本地回环监听器 / 自定义 scheme：**必须 query** —— 这两者根本拿不到 fragment
//     （fragment 不会被浏览器发出，也不会出现在交给系统的 URI 里）
func returnUsesFragment(base, target string) bool {
	return sameOrigin(base, target)
}

// oidcDonePage OIDC 授权成功后的落地页。
// token 由 oidcCallback 放在 fragment 里回跳（fragment 不会发给服务端），只能用 JS 取出展示，
// 用户复制后粘贴到客户端登录页完成登录。
func (a *apiv1) oidcDonePage(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	_, _ = io.WriteString(w, oidcDoneHTML)
}

const oidcDoneHTML = `<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>雁书 · OIDC 登录</title>
<style>
body{font-family:system-ui,-apple-system,"PingFang SC","Microsoft YaHei",sans-serif;background:#f5f6f8;display:flex;align-items:center;justify-content:center;min-height:100vh;margin:0}
.card{background:#fff;border-radius:12px;padding:32px;max-width:560px;width:92%;box-shadow:0 2px 12px rgba(0,0,0,.08)}
h1{font-size:18px;margin:0 0 16px}
code{display:block;background:#f5f6f8;border:1px solid #e3e5e8;border-radius:6px;padding:10px;word-break:break-all;font-size:12px;margin:8px 0 16px}
button{background:#2e7d32;color:#fff;border:0;border-radius:6px;padding:8px 16px;font-size:14px;cursor:pointer}
.err{color:#c62828;font-size:14px}
.hint{color:#555;font-size:13px;line-height:1.7;margin:6px 0}
</style></head><body><div class="card">
<h1>雁书 · OIDC 登录</h1>
<div id="box" class="hint">正在读取令牌…</div>
<script>
(function () {
  var m = /(?:^|[#?])token=([^&]+)/.exec(location.hash || "");
  var box = document.getElementById("box");
  if (!m) {
    box.innerHTML = '<span class="err">未找到 token</span>' +
      '<p class="hint">如果刚从身份提供商返回，请检查地址栏里 #token= 是否存在；' +
      '也可能是授权 state 已过期（5 分钟），请重新点击「使用 XX 登录」。</p>';
    return;
  }
  var t = decodeURIComponent(m[1]);
  box.innerHTML = '<p class="hint">复制下面的令牌，粘贴到雁书客户端登录页的「OIDC Token」输入框，再点「完成 OIDC 登录」。</p>' +
    '<code id="tok"></code><button id="btn">复制令牌</button><p class="hint" id="tip"></p>';
  document.getElementById("tok").textContent = t;
  document.getElementById("btn").onclick = function () {
    var tip = document.getElementById("tip");
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(t).then(
        function () { tip.textContent = "已复制"; },
        function () { tip.textContent = "复制失败，请手动选中上面的令牌复制"; });
    } else {
      tip.textContent = "请手动选中上面的令牌复制";
    }
  };
})();
</script>
</div></body></html>
`

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
	returnURI, err := checkReturnTo(base, returnTo, a.cfg.OIDCReturnAllowlist)
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
	returnURI, err := checkReturnTo(base, returnTo, a.cfg.OIDCReturnAllowlist)
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
		// yid_changed=1 的语义是「用户已主动改过雁书号」。OIDC 建号时 yid 还是自动生成的，
		// 置 1 会让这个账号永远没有机会改成自己的号 —— 这正是「OIDC 之后改不了雁书号」的原因。
		if _, err := a.db.Exec(
			`INSERT INTO user(uid, username, password_hash, nickname, yid, yid_changed, email, created_at) VALUES(?,?,?,?,?,?,?,?)`,
			uid, username, hash, nick, yid, 0, nullIfEmpty(email), now); err != nil {
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

	// OIDC 登录同 HTTP 登录：取当前令牌版本签发，并尊重封禁状态
	st, err := userstate.Get(r.Context(), a.db.DB, a.rdb, uid)
	if err != nil {
		fail(w, 500, err)
		return
	}
	if st.Disabled {
		fail(w, 403, errors.New("account disabled"))
		return
	}
	tok, _ := auth.MakeToken(a.cfg.JWTSecret, uid, "oidc", st.TokenVersion)
	// 回跳到发起登录时的页面（同源校验已在 authorize/callback 两处做过）。
	// 同源页面用 fragment（不进服务端日志/Referer，页面 JS 读取后自动登录）；
	// 本地回环监听器与自定义 scheme 拿不到 fragment，必须放 query 才能收到令牌。
	target := returnURI
	if returnUsesFragment(base, returnURI) {
		target += "#token=" + tok + "&uid=" + uid
	} else {
		sep := "?"
		if strings.Contains(returnURI, "?") {
			sep = "&"
		}
		target += sep + "token=" + url.QueryEscape(tok) + "&uid=" + url.QueryEscape(uid)
	}
	http.Redirect(w, r, target, http.StatusFound)
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
