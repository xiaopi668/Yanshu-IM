// logic：HTTP 业务服务。注册/登录/好友/会话/历史/对象存储 + 二期（雁书号/通讯录/朋友圈）。
package main

import (
	"context"
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"log"
	"net/http"
	"regexp"
	"strings"
	"time"

	"github.com/redis/go-redis/v9"
	"im/internal/auth"
	"im/internal/config"
	"im/internal/hub"
	"im/internal/messaging"
	"im/internal/migrate"
	"im/internal/siteconf"
	"im/internal/storage"
	"im/internal/store"
)

type apiv1 struct {
	cfg   *config.Config
	db    *store.DB
	msg   *messaging.Service
	minio *storage.ObjectStore
	hub   *hub.Hub
	rdb   *redis.Client
}

var yidRe = regexp.MustCompile(`^[a-zA-Z][a-zA-Z0-9_-]{4,19}$`)

func main() {
	cfg := config.Load()
	if err := cfg.CheckSecrets(); err != nil {
		log.Fatalf("[logic] %v", err)
	}
	sqldb, err := store.NewMySQL(cfg.MySQLDSN)
	if err != nil {
		log.Fatalf("mysql: %v", err)
	}
	migrate.Run(sqldb)
	rdb := store.NewRedis(cfg.RedisAddr, cfg.RedisPass)
	db := store.Wrap(sqldb)
	h := hub.New()
	// 接入 Redis 总线：logic 自身不持有 WS 连接，只发布（好友/群事件推给 gateway）
	h.AttachBus(context.Background(), rdb, false)
	msgSvc := messaging.New(sqldb, rdb, h, 2)
	minioSvc, err := storage.NewObjectStore(cfg)
	if err != nil {
		log.Printf("[logic] object storage unavailable (attachments disabled): %v", err)
	}

	a := &apiv1{cfg: cfg, db: db, msg: msgSvc, minio: minioSvc, hub: h, rdb: rdb}
	a.startArchiver()

	mux := http.NewServeMux()
	// 一期
	mux.HandleFunc("POST /v1/register", a.register)
	mux.HandleFunc("POST /v1/login", a.login)
	mux.Handle("GET /v1/me", a.authed(a.me))
	mux.Handle("GET /v1/friends", a.authed(a.listFriends))
	mux.Handle("POST /v1/friends", a.authed(a.addFriend))
	mux.Handle("GET /v1/conversations", a.authed(a.listConversations))
	mux.Handle("POST /v1/conversations/single", a.authed(a.createSingle))
	mux.Handle("POST /v1/conversations/group", a.authed(a.createGroup))
	mux.Handle("GET /v1/conversations/{id}/members", a.authed(a.listMembers))
	mux.Handle("GET /v1/conversations/{id}/history", a.authed(a.history))
	mux.Handle("POST /v1/upload-token", a.authed(a.uploadToken))
	mux.Handle("GET /v1/download", a.authed(a.download))
	// 二期：雁书号
	mux.Handle("GET /v1/users/search", a.authed(a.searchUser))
	// 四期：站点配置 / 邮箱验证码 / OIDC
	mux.HandleFunc("GET /v1/site-config", a.publicSiteConfig)
	mux.HandleFunc("POST /v1/email/send-code", a.sendEmailCode)
	mux.HandleFunc("GET /v1/oidc/{name}/authorize", a.oidcAuthorize)
	mux.HandleFunc("GET /v1/oidc/{name}/callback", a.oidcCallback)
	mux.Handle("PUT /v1/me/yid", a.authed(a.changeYid))
	mux.Handle("PUT /v1/me", a.authed(a.updateMe))
	// 二期：通讯录
	mux.Handle("POST /v1/contacts/request", a.authed(a.contactRequest))
	mux.Handle("POST /v1/contacts/{id}/accept", a.authed(a.contactAccept))
	mux.Handle("POST /v1/contacts/{id}/reject", a.authed(a.contactReject))
	mux.Handle("GET /v1/contacts", a.authed(a.listContacts))
	mux.Handle("GET /v1/contacts/requests", a.authed(a.listContactRequests))
	mux.Handle("PUT /v1/contacts/{uid}/remark", a.authed(a.setRemark))
	mux.Handle("DELETE /v1/contacts/{uid}", a.authed(a.removeContact))
	// 二期：朋友圈
	mux.Handle("POST /v1/moments", a.authed(a.createMoment))
	mux.Handle("GET /v1/moments/feed", a.authed(a.momentFeed))
	mux.Handle("GET /v1/moments/mine", a.authed(a.momentMine))
	mux.Handle("POST /v1/moments/{id}/like", a.authed(a.momentLike))
	mux.Handle("DELETE /v1/moments/{id}/like", a.authed(a.momentUnlike))
	mux.Handle("POST /v1/moments/{id}/comment", a.authed(a.momentComment))
	mux.Handle("DELETE /v1/moments/{id}", a.authed(a.deleteMoment))
	// 三期：搜索 / 群管理 / 头像
	mux.Handle("GET /v1/search", a.authed(a.searchMessages))
	mux.Handle("GET /v1/groups/{id}/info", a.authed(a.getGroupInfo))
	mux.Handle("PUT /v1/groups/{id}/announcement", a.authed(a.setAnnouncement))
	mux.Handle("DELETE /v1/groups/{id}/members/{uid}", a.authed(a.kickMember))
	mux.Handle("PUT /v1/groups/{id}/roles", a.authed(a.setGroupRole))
	mux.Handle("POST /v1/conversations/{id}/members", a.authed(a.addMembersEnhanced))
	mux.Handle("PUT /v1/me/avatar", a.authed(a.setAvatar))

	log.Printf("[logic] listening on %s", cfg.LogicAddr)
	log.Fatal(http.ListenAndServe(cfg.LogicAddr, cors(cfg, mux)))
}

// cors 允许 Web 端跨源调用（私有化部署场景，浏览器客户端与 API 常不同源）。
// 配置了 IM_ALLOWED_ORIGINS 时严格匹配并回显该来源，否则保持原有的 * 行为。
func cors(cfg *config.Config, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		origin := r.Header.Get("Origin")
		if len(cfg.AllowedOrigins) > 0 {
			if !cfg.OriginAllowed(origin) {
				w.WriteHeader(http.StatusForbidden)
				return
			}
			if origin != "" {
				w.Header().Set("Access-Control-Allow-Origin", origin)
			}
		} else {
			w.Header().Set("Access-Control-Allow-Origin", "*")
		}
		w.Header().Add("Vary", "Origin")
		w.Header().Set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
		w.Header().Set("Access-Control-Allow-Headers", "Content-Type, Authorization")
		if r.Method == http.MethodOptions {
			w.WriteHeader(http.StatusNoContent)
			return
		}
		next.ServeHTTP(w, r)
	})
}

// ---------- auth middleware ----------

type authedHandler func(w http.ResponseWriter, r *http.Request, uid string)

func (a *apiv1) authed(next authedHandler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		tok := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
		if tok == "" {
			// 附件下载等场景允许 query token（URL 会存进消息历史）
			tok = r.URL.Query().Get("token")
		}
		claims, err := auth.ParseToken(a.cfg.JWTSecret, tok)
		if err != nil {
			writeJSON(w, 401, map[string]string{"error": "unauthorized"})
			return
		}
		next(w, r, claims.UID)
	})
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func fail(w http.ResponseWriter, status int, err error) {
	writeJSON(w, status, map[string]string{"error": err.Error()})
}

// ---------- 注册 / 登录 ----------

func genYid() string {
	b := make([]byte, 5)
	_, _ = rand.Read(b)
	return "ys" + hex.EncodeToString(b)
}

func genID() string {
	b := make([]byte, 8)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

func (a *apiv1) register(w http.ResponseWriter, r *http.Request) {
	if err := a.rateLimit(r, "register", a.cfg.AuthRateLimit, 5*time.Minute); err != nil {
		fail(w, 429, err)
		return
	}
	var req struct {
		Username       string `json:"username"`
		Password       string `json:"password"`
		Nickname       string `json:"nickname"`
		Yid            string `json:"yid"`
		Email          string `json:"email"`
		EmailCode      string `json:"email_code"`
		TurnstileToken string `json:"turnstile_token"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		fail(w, 400, err)
		return
	}
	if len(req.Username) < 3 || len(req.Password) < 6 {
		fail(w, 400, errors.New("username>=3, password>=6"))
		return
	}
	// 认证增强：注册开关 / Turnstile / 邮箱验证码
	acfg := a.siteConf()
	if !acfg.RegistrationEnabled {
		fail(w, 403, errors.New("站点已关闭注册"))
		return
	}
	if acfg.TurnstileEnabled {
		if err := a.checkTurnstile(acfg, req.TurnstileToken, clientIP(r, a.cfg.TrustProxy)); err != nil {
			fail(w, 403, err)
			return
		}
	}
	email := strings.ToLower(strings.TrimSpace(req.Email))
	if acfg.EmailCodeEnabled {
		if email == "" {
			fail(w, 400, errors.New("请填写邮箱并完成验证码校验"))
			return
		}
		if err := siteconf.CheckEmailCode(a.rdb, email, req.EmailCode); err != nil {
			fail(w, 400, err)
			return
		}
	}
	yid := req.Yid
	if yid == "" {
		yid = genYid()
		for i := 0; i < 5; i++ { // 撞号重试
			var n int
			if err := a.db.QueryRow(`SELECT COUNT(*) FROM user WHERE yid=?`, yid).Scan(&n); err != nil {
				break
			}
			if n == 0 {
				break
			}
			yid = genYid()
		}
	} else {
		if !yidRe.MatchString(yid) {
			fail(w, 400, errors.New("yid: 5-20位，字母开头，可含数字/_/-"))
			return
		}
	}
	hash, err := auth.HashPassword(req.Password)
	if err != nil {
		fail(w, 500, err)
		return
	}
	uid := a.msg.ID.Next()
	nick := req.Nickname
	if nick == "" {
		nick = req.Username
	}
	_, err = a.db.Exec(
		`INSERT INTO user(uid, username, password_hash, nickname, yid, yid_changed, email, created_at) VALUES(?,?,?,?,?,?,?,?)`,
		uid, req.Username, hash, nick, yid, 0, nullIfEmpty(email), time.Now().UnixMilli())
	if err != nil {
		if strings.Contains(err.Error(), "uk_yid") {
			fail(w, 409, errors.New("雁书号已被占用"))
			return
		}
		fail(w, 409, errors.New("username taken"))
		return
	}
	tok, _ := auth.MakeToken(a.cfg.JWTSecret, uid, "")
	writeJSON(w, 200, map[string]any{"uid": uid, "yid": yid, "token": tok})
}

func (a *apiv1) login(w http.ResponseWriter, r *http.Request) {
	if err := a.rateLimit(r, "login", a.cfg.AuthRateLimit, 5*time.Minute); err != nil {
		fail(w, 429, err)
		return
	}
	var req struct {
		Username       string `json:"username"`
		Password       string `json:"password"`
		Platform       string `json:"platform"`
		TurnstileToken string `json:"turnstile_token"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		fail(w, 400, err)
		return
	}
	var uid, hash string
	err := a.db.QueryRow(`SELECT uid, password_hash FROM user WHERE username=? OR yid=?`,
		req.Username, req.Username).Scan(&uid, &hash)
	if errors.Is(err, sql.ErrNoRows) {
		fail(w, 401, errors.New("bad credentials"))
		return
	}
	if err != nil {
		fail(w, 500, err)
		return
	}
	if !auth.CheckPassword(hash, req.Password) {
		fail(w, 401, errors.New("bad credentials"))
		return
	}
	acfg := a.siteConf()
	if acfg.TurnstileEnabled {
		if err := a.checkTurnstile(acfg, req.TurnstileToken, clientIP(r, a.cfg.TrustProxy)); err != nil {
			fail(w, 403, err)
			return
		}
	}
	// 封禁检查
	var disabled int
	if err := a.db.QueryRow(`SELECT disabled FROM user_state WHERE uid=?`, uid).Scan(&disabled); err == nil && disabled == 1 {
		fail(w, 403, errors.New("account disabled"))
		return
	}
	tok, _ := auth.MakeToken(a.cfg.JWTSecret, uid, req.Platform)
	writeJSON(w, 200, map[string]string{"uid": uid, "token": tok})
}

func (a *apiv1) me(w http.ResponseWriter, r *http.Request, uid string) {
	var u struct {
		UID      string
		Username string
		Nickname string
		Avatar   string
		Yid      string
	}
	var changed int
	err := a.db.QueryRow(`SELECT uid, username, nickname, avatar_url, COALESCE(yid,''), yid_changed FROM user WHERE uid=?`, uid).
		Scan(&u.UID, &u.Username, &u.Nickname, &u.Avatar, &u.Yid, &changed)
	if err != nil {
		fail(w, 404, err)
		return
	}
	writeJSON(w, 200, map[string]any{
		"uid": u.UID, "username": u.Username, "nickname": u.Nickname, "avatar": u.Avatar,
		"yid": u.Yid, "yid_changed": changed == 1,
	})
}

// changeYid 改雁书号（仅一次）
func (a *apiv1) changeYid(w http.ResponseWriter, r *http.Request, uid string) {
	var req struct {
		Yid string `json:"yid"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		fail(w, 400, err)
		return
	}
	if !yidRe.MatchString(req.Yid) {
		fail(w, 400, errors.New("yid: 5-20位，字母开头，可含数字/_/-"))
		return
	}
	var changed int
	var cur string
	if err := a.db.QueryRow(`SELECT yid_changed, COALESCE(yid,'') FROM user WHERE uid=?`, uid).Scan(&changed, &cur); err != nil {
		fail(w, 404, err)
		return
	}
	if changed == 1 {
		fail(w, 403, errors.New("雁书号只能修改一次"))
		return
	}
	if _, err := a.db.Exec(`UPDATE user SET yid=?, yid_changed=1 WHERE uid=?`, req.Yid, uid); err != nil {
		if strings.Contains(err.Error(), "uk_yid") {
			fail(w, 409, errors.New("雁书号已被占用"))
			return
		}
		fail(w, 500, err)
		return
	}
	writeJSON(w, 200, map[string]string{"yid": req.Yid})
}

// searchUser 按雁书号精确查找（添加好友用）
func (a *apiv1) searchUser(w http.ResponseWriter, r *http.Request, uid string) {
	yid := r.URL.Query().Get("yid")
	if yid == "" {
		fail(w, 400, errors.New("missing yid"))
		return
	}
	var fu, nick string
	err := a.db.QueryRow(`SELECT uid, nickname FROM user WHERE yid=?`, yid).Scan(&fu, &nick)
	if errors.Is(err, sql.ErrNoRows) {
		fail(w, 404, errors.New("用户不存在"))
		return
	}
	if err != nil {
		fail(w, 500, err)
		return
	}
	writeJSON(w, 200, map[string]string{"uid": fu, "yid": yid, "nickname": nick})
}

// updateMe 修改个人资料（昵称等）
func (a *apiv1) updateMe(w http.ResponseWriter, r *http.Request, uid string) {
	var req struct {
		Nickname string `json:"nickname"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		fail(w, 400, err)
		return
	}
	nick := strings.TrimSpace(req.Nickname)
	if nick == "" {
		fail(w, 400, errors.New("nickname required"))
		return
	}
	if _, err := a.db.Exec(`UPDATE user SET nickname=? WHERE uid=?`, nick, uid); err != nil {
		fail(w, 500, err)
		return
	}
	writeJSON(w, 200, map[string]bool{"ok": true})
}
