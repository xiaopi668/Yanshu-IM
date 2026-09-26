// admin：私有化管理后台。Bearer Token 鉴权（IM_ADMIN_TOKEN），端口 10003。
// 功能：用户管理（列表/搜索/封禁/重置密码）、统计、聊天记录归档触发、单页控制台。
package main

import (
	"database/sql"
	"encoding/json"
	"errors"
	"log"
	"net/http"
	"strings"
	"time"

	_ "embed"

	"github.com/redis/go-redis/v9"

	"im/internal/archive"
	"im/internal/auth"
	"im/internal/siteconf"
	"im/internal/config"
	"im/internal/hub"
	"im/internal/messaging"
	"im/internal/migrate"
	"im/internal/storage"
	"im/internal/store"
)

//go:embed admin.html
var adminHTML []byte

type admin struct {
	cfg   *config.Config
	db    *store.DB
	msg   *messaging.Service
	minio *storage.ObjectStore
	rdb   *redis.Client
}

func main() {
	cfg := config.Load()
	sqldb, err := store.NewMySQL(cfg.MySQLDSN)
	if err != nil {
		log.Fatalf("mysql: %v", err)
	}
	migrate.Run(sqldb)
	rdb := store.NewRedis(cfg.RedisAddr, cfg.RedisPass)
	h := hub.New()
	msgSvc := messaging.New(sqldb, rdb, h, 3)
	minioSvc, err := storage.NewObjectStore(cfg)
	if err != nil {
		log.Printf("[admin] object storage unavailable: %v", err)
	}
	a := &admin{cfg: cfg, db: store.Wrap(sqldb), msg: msgSvc, minio: minioSvc, rdb: rdb}

	mux := http.NewServeMux()
	mux.HandleFunc("GET /admin", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_, _ = w.Write(adminHTML)
	})
	mux.HandleFunc("POST /admin/login", a.login)
	a.admined(mux, "GET /admin/users", a.listUsers)
	a.admined(mux, "POST /admin/users/{uid}/disable", a.disable)
	a.admined(mux, "POST /admin/users/{uid}/enable", a.enable)
	a.admined(mux, "POST /admin/users/{uid}/reset-password", a.resetPassword)
	a.admined(mux, "GET /admin/stats", a.stats)
	a.admined(mux, "POST /admin/archive/{day}", a.archiveDayHandler)
	a.admined(mux, "GET /admin/archives", a.archives)
	a.admined(mux, "GET /admin/conversations", a.listConversations)
	a.admined(mux, "GET /admin/site-config", a.getSiteConfig)
	a.admined(mux, "PUT /admin/site-config", a.putSiteConfig)

	log.Printf("[admin] listening on %s", cfg.AdminAddr)
	log.Fatal(http.ListenAndServe(cfg.AdminAddr, cors(mux)))
}

func (a *admin) login(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Token string `json:"token"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		fail(w, 400, err)
		return
	}
	if req.Token != a.cfg.AdminToken {
		fail(w, 401, errors.New("invalid token"))
		return
	}
	writeJSON(w, 200, map[string]bool{"ok": true})
}

func (a *admin) admined(mux *http.ServeMux, pattern string, h http.HandlerFunc) {
	mux.Handle(pattern, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		tok := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
		if tok != a.cfg.AdminToken {
			fail(w, 401, errors.New("unauthorized"))
			return
		}
		h(w, r)
	}))
}

func (a *admin) listUsers(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query().Get("q")
	var rows *sql.Rows
	var err error
	if q != "" {
		rows, err = a.db.Query(`
			SELECT u.uid, u.username, u.nickname, COALESCE(u.yid,''), u.created_at, COALESCE(s.disabled,0)
			FROM user u LEFT JOIN user_state s ON s.uid=u.uid
			WHERE u.uid=? OR u.username LIKE ? OR u.nickname LIKE ? OR u.yid LIKE ? LIMIT 100`,
			q, "%"+q+"%", "%"+q+"%", "%"+q+"%")
	} else {
		rows, err = a.db.Query(`
			SELECT u.uid, u.username, u.nickname, COALESCE(u.yid,''), u.created_at, COALESCE(s.disabled,0)
			FROM user u LEFT JOIN user_state s ON s.uid=u.uid ORDER BY u.created_at DESC LIMIT 200`)
	}
	if err != nil {
		fail(w, 500, err)
		return
	}
	defer rows.Close()
	type user struct {
		UID      string `json:"uid"`
		Username string `json:"username"`
		Nickname string `json:"nickname"`
		Yid      string `json:"yid"`
		Created  int64  `json:"created_at"`
		Disabled bool   `json:"disabled"`
	}
	out := []user{}
	for rows.Next() {
		var u user
		var dis int
		if err := rows.Scan(&u.UID, &u.Username, &u.Nickname, &u.Yid, &u.Created, &dis); err != nil {
			fail(w, 500, err)
			return
		}
		u.Disabled = dis == 1
		out = append(out, u)
	}
	writeJSON(w, 200, out)
}

func (a *admin) disable(w http.ResponseWriter, r *http.Request) {
	a.setState(w, r, 1)
}

func (a *admin) enable(w http.ResponseWriter, r *http.Request) {
	a.setState(w, r, 0)
}

func (a *admin) setState(w http.ResponseWriter, r *http.Request, v int) {
	uid := r.PathValue("uid")
	if _, err := a.db.Exec(
		`INSERT INTO user_state(uid, disabled) VALUES(?,?) ON DUPLICATE KEY UPDATE disabled=?`,
		uid, v, v); err != nil {
		fail(w, 500, err)
		return
	}
	writeJSON(w, 200, map[string]bool{"ok": true})
}

func (a *admin) resetPassword(w http.ResponseWriter, r *http.Request) {
	uid := r.PathValue("uid")
	var req struct {
		Password string `json:"password"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		fail(w, 400, err)
		return
	}
	if len(req.Password) < 6 {
		fail(w, 400, errors.New("password>=6"))
		return
	}
	hash, err := auth.HashPassword(req.Password)
	if err != nil {
		fail(w, 500, err)
		return
	}
	if _, err := a.db.Exec(`UPDATE user SET password_hash=? WHERE uid=?`, hash, uid); err != nil {
		fail(w, 500, err)
		return
	}
	writeJSON(w, 200, map[string]bool{"ok": true})
}

func (a *admin) stats(w http.ResponseWriter, r *http.Request) {
	out := map[string]any{}
	var users, msgs, today int64
	_ = a.db.QueryRow(`SELECT COUNT(*) FROM user`).Scan(&users)
	_ = a.db.QueryRow(`SELECT COUNT(*) FROM message`).Scan(&msgs)
	todayStart := time.Now().Format("20060102")
	_ = a.db.QueryRow(`SELECT COUNT(*) FROM message WHERE sent_at>=?`,
		dayStart(todayStart)).Scan(&today)
	out["users"] = users
	out["messages_total"] = msgs
	out["messages_today"] = today
	var moments int64
	_ = a.db.QueryRow(`SELECT COUNT(*) FROM moment`).Scan(&moments)
	out["moments"] = moments
	// 在线连接数（gateway 周期性写入 Redis）
	if a.rdb != nil {
		if n, err := a.rdb.Get(r.Context(), "im:online").Int(); err == nil {
			out["online"] = n
		}
	}
	writeJSON(w, 200, out)
}

func (a *admin) archiveDayHandler(w http.ResponseWriter, r *http.Request) {
	day := r.PathValue("day")
	archive.ArchiveDay(a.db.DB, a.minio, day)
	writeJSON(w, 200, map[string]bool{"ok": true})
}

func (a *admin) archives(w http.ResponseWriter, r *http.Request) {
	rows, err := a.db.Query(`SELECT day, conversation_id, count, object_key, created_at FROM archive_log ORDER BY day DESC LIMIT 200`)
	if err != nil {
		fail(w, 500, err)
		return
	}
	defer rows.Close()
	type ar struct {
		Day      string `json:"day"`
		ConvID   string `json:"conversation_id"`
		Count    int64  `json:"count"`
		ObjectKey string `json:"object_key"`
		Created  int64  `json:"created_at"`
	}
	out := []ar{}
	for rows.Next() {
		var it ar
		if err := rows.Scan(&it.Day, &it.ConvID, &it.Count, &it.ObjectKey, &it.Created); err != nil {
			fail(w, 500, err)
			return
		}
		out = append(out, it)
	}
	writeJSON(w, 200, out)
}

func (a *admin) listConversations(w http.ResponseWriter, r *http.Request) {
	rows, err := a.db.Query(`
		SELECT c.conversation_id, c.type, c.created_at,
		       (SELECT COUNT(*) FROM conversation_member m WHERE m.conversation_id=c.conversation_id) AS members,
		       (SELECT COUNT(*) FROM message msg WHERE msg.conversation_id=c.conversation_id) AS msgs
		FROM conversation c ORDER BY c.created_at DESC LIMIT 200`)
	if err != nil {
		fail(w, 500, err)
		return
	}
	defer rows.Close()
	type conv struct {
		ID      string `json:"id"`
		Type    string `json:"type"`
		Created int64  `json:"created_at"`
		Members int64  `json:"members"`
		Msgs    int64  `json:"msgs"`
	}
	out := []conv{}
	for rows.Next() {
		var c conv
		if err := rows.Scan(&c.ID, &c.Type, &c.Created, &c.Members, &c.Msgs); err != nil {
			fail(w, 500, err)
			return
		}
		out = append(out, c)
	}
	writeJSON(w, 200, out)
}


// ---------- 站点配置 ----------

func (a *admin) getSiteConfig(w http.ResponseWriter, r *http.Request) {
	c, err := siteconf.Load(a.db.DB)
	if err != nil {
		fail(w, 500, err)
		return
	}
	writeJSON(w, 200, c)
}

func (a *admin) putSiteConfig(w http.ResponseWriter, r *http.Request) {
	var c siteconf.Conf
	if err := json.NewDecoder(r.Body).Decode(&c); err != nil {
		fail(w, 400, err)
		return
	}
	if err := siteconf.Save(a.db.DB, c); err != nil {
		fail(w, 500, err)
		return
	}
	writeJSON(w, 200, map[string]bool{"ok": true})
}
