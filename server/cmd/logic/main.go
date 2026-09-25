// logic：HTTP 业务服务。注册/登录/好友/会话/历史消息/对象存储直传凭证。
package main

import (
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"net/http"
	"sort"
	"strconv"
	"strings"
	"time"

	"im/internal/auth"
	"im/internal/config"
	"im/internal/hub"
	"im/internal/messaging"
	"im/internal/storage"
	"im/internal/store"
)

type apiv1 struct {
	cfg   *config.Config
	db    *store.DB
	msg   *messaging.Service
	minio *storage.Minio
}

func main() {
	cfg := config.Load()
	sqldb, err := store.NewMySQL(cfg.MySQLDSN)
	if err != nil {
		log.Fatalf("mysql: %v", err)
	}
	rdb := store.NewRedis(cfg.RedisAddr, cfg.RedisPass)
	db := store.Wrap(sqldb)
	h := newLocalHub()
	msgSvc := messaging.New(sqldb, rdb, h, 2)
	minioSvc, err := storage.NewMinio(cfg.MinioEndpoint, cfg.MinioAccessKey, cfg.MinioSecretKey, cfg.MinioBucket, cfg.MinioSecure)
	if err != nil {
		log.Printf("[logic] minio unavailable (attachments disabled): %v", err)
	}

	a := &apiv1{cfg: cfg, db: db, msg: msgSvc, minio: minioSvc}

	mux := http.NewServeMux()
	mux.HandleFunc("POST /v1/register", a.register)
	mux.HandleFunc("POST /v1/login", a.login)
	mux.Handle("GET /v1/me", a.authed(a.me))
	mux.Handle("GET /v1/friends", a.authed(a.listFriends))
	mux.Handle("POST /v1/friends", a.authed(a.addFriend))
	mux.Handle("GET /v1/conversations", a.authed(a.listConversations))
	mux.Handle("POST /v1/conversations/single", a.authed(a.createSingle))
	mux.Handle("POST /v1/conversations/group", a.authed(a.createGroup))
	mux.Handle("GET /v1/conversations/{id}/members", a.authed(a.listMembers))
	mux.Handle("POST /v1/conversations/{id}/members", a.authed(a.addMembers))
	mux.Handle("GET /v1/conversations/{id}/history", a.authed(a.history))
	mux.Handle("POST /v1/upload-token", a.authed(a.uploadToken))
	mux.Handle("GET /v1/download", a.authed(a.download))

	log.Printf("[logic] listening on %s", cfg.LogicAddr)
	log.Fatal(http.ListenAndServe(cfg.LogicAddr, cors(mux)))
}

// cors 允许 Web 端跨源调用（私有化部署场景，浏览器客户端与 API 常不同源）
func cors(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Access-Control-Allow-Origin", "*")
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

func (a *apiv1) register(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Username string `json:"username"`
		Password string `json:"password"`
		Nickname string `json:"nickname"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		fail(w, 400, err)
		return
	}
	if len(req.Username) < 3 || len(req.Password) < 6 {
		fail(w, 400, errors.New("username>=3, password>=6"))
		return
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
	_, err = a.db.Exec(`INSERT INTO user(uid, username, password_hash, nickname, created_at) VALUES(?,?,?,?,?)`,
		uid, req.Username, hash, nick, time.Now().UnixMilli())
	if err != nil {
		fail(w, 409, errors.New("username taken"))
		return
	}
	tok, _ := auth.MakeToken(a.cfg.JWTSecret, uid, "")
	writeJSON(w, 200, map[string]string{"uid": uid, "token": tok})
}

func (a *apiv1) login(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Username string `json:"username"`
		Password string `json:"password"`
		Platform string `json:"platform"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		fail(w, 400, err)
		return
	}
	var uid, hash string
	err := a.db.QueryRow(`SELECT uid, password_hash FROM user WHERE username=?`, req.Username).Scan(&uid, &hash)
	if errors.Is(err, store.ErrNoRows) {
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
	tok, _ := auth.MakeToken(a.cfg.JWTSecret, uid, req.Platform)
	writeJSON(w, 200, map[string]string{"uid": uid, "token": tok})
}

func (a *apiv1) me(w http.ResponseWriter, r *http.Request, uid string) {
	var u struct {
		UID      string `json:"uid"`
		Username string `json:"username"`
		Nickname string `json:"nickname"`
		Avatar   string `json:"avatar"`
	}
	err := a.db.QueryRow(`SELECT uid, username, nickname, avatar_url FROM user WHERE uid=?`, uid).
		Scan(&u.UID, &u.Username, &u.Nickname, &u.Avatar)
	if err != nil {
		fail(w, 404, err)
		return
	}
	writeJSON(w, 200, u)
}

// ---------- 好友 ----------

func (a *apiv1) listFriends(w http.ResponseWriter, r *http.Request, uid string) {
	rows, err := a.db.Query(`
		SELECT f.friend_uid, u.username, u.nickname, u.avatar_url
		FROM friend f JOIN user u ON u.uid=f.friend_uid WHERE f.owner_uid=?`, uid)
	if err != nil {
		fail(w, 500, err)
		return
	}
	defer rows.Close()
	type friend struct {
		UID      string `json:"uid"`
		Username string `json:"username"`
		Nickname string `json:"nickname"`
		Avatar   string `json:"avatar"`
	}
	out := []friend{}
	for rows.Next() {
		var f friend
		if err := rows.Scan(&f.UID, &f.Username, &f.Nickname, &f.Avatar); err != nil {
			fail(w, 500, err)
			return
		}
		out = append(out, f)
	}
	writeJSON(w, 200, out)
}

func (a *apiv1) addFriend(w http.ResponseWriter, r *http.Request, uid string) {
	var req struct {
		FriendUID string `json:"friend_uid"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		fail(w, 400, err)
		return
	}
	var n int
	if err := a.db.QueryRow(`SELECT COUNT(*) FROM user WHERE uid=?`, req.FriendUID).Scan(&n); err != nil || n == 0 {
		fail(w, 404, errors.New("user not found"))
		return
	}
	now := time.Now().UnixMilli()
	if _, err := a.db.Exec(
		`INSERT IGNORE INTO friend(owner_uid, friend_uid, created_at) VALUES(?,?,?),(?,?,?)`,
		uid, req.FriendUID, now, req.FriendUID, uid, now); err != nil {
		fail(w, 500, err)
		return
	}
	writeJSON(w, 200, map[string]bool{"ok": true})
}

// ---------- 会话 ----------

func (a *apiv1) listConversations(w http.ResponseWriter, r *http.Request, uid string) {
	rows, err := a.db.Query(`
		SELECT c.conversation_id, c.type, m.read_seq,
		       COALESCE(g.name, '') AS title,
		       (SELECT seq FROM message WHERE message.conversation_id=c.conversation_id ORDER BY seq DESC LIMIT 1) AS last_seq
		FROM conversation_member m
		JOIN conversation c ON c.conversation_id=m.conversation_id
		LEFT JOIN group_info g ON g.group_id=c.conversation_id
		WHERE m.uid=?`, uid)
	if err != nil {
		fail(w, 500, err)
		return
	}
	defer rows.Close()
	type conv struct {
		ID      string  `json:"id"`
		Type    string  `json:"type"`
		Title   string  `json:"title"`
		LastSeq uint64  `json:"last_seq"`
		ReadSeq uint64  `json:"read_seq"`
	}
	out := []conv{}
	convIDs := []string{}
	byID := map[string]*conv{}
	for rows.Next() {
		var c conv
		var lastSeq sql.NullInt64
		if err := rows.Scan(&c.ID, &c.Type, &c.ReadSeq, &c.Title, &lastSeq); err != nil {
			fail(w, 500, err)
			return
		}
		c.LastSeq = uint64(lastSeq.Int64)
		if c.Type == "single" {
			convIDs = append(convIDs, c.ID)
		}
		byID[c.ID] = &c
		out = append(out, c)
	}
	if err := rows.Err(); err != nil {
		fail(w, 500, err)
		return
	}
	// 单聊标题 = 对端昵称
	if len(convIDs) > 0 {
		members, err := a.db.MembersOf(convIDs)
		if err != nil {
			fail(w, 500, err)
			return
		}
		allPeers := []string{}
		for _, list := range members {
			allPeers = append(allPeers, list...)
		}
		names, err := a.db.NicknamesOf(exclude(uniq(allPeers), uid))
		if err != nil {
			fail(w, 500, err)
			return
		}
		for i := range out {
			if out[i].Type != "single" {
				continue
			}
			var peer string
			for _, m := range members[out[i].ID] {
				if m != uid {
					peer = m
				}
			}
			out[i].Title = names[peer]
		}
	}
	writeJSON(w, 200, out)
}

func exclude(list []string, s string) []string {
	out := make([]string, 0, len(list))
	for _, x := range list {
		if x != s {
			out = append(out, x)
		}
	}
	return out
}

func (a *apiv1) createSingle(w http.ResponseWriter, r *http.Request, uid string) {
	var req struct {
		PeerUID string `json:"peer_uid"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		fail(w, 400, err)
		return
	}
	if req.PeerUID == uid {
		fail(w, 400, errors.New("cannot chat with self"))
		return
	}
	var n int
	if err := a.db.QueryRow(`SELECT COUNT(*) FROM user WHERE uid=?`, req.PeerUID).Scan(&n); err != nil || n == 0 {
		fail(w, 404, errors.New("peer not found"))
		return
	}
	uidA, uidB := uid, req.PeerUID
	if uidA > uidB {
		uidA, uidB = uidB, uidA
	}
	convID := fmt.Sprintf("s_%s_%s", uidA, uidB)
	if err := a.ensureConversation(convID, "single", []string{uidA, uidB}, uid); err != nil {
		fail(w, 500, err)
		return
	}
	writeJSON(w, 200, map[string]string{"id": convID})
}

func (a *apiv1) createGroup(w http.ResponseWriter, r *http.Request, uid string) {
	var req struct {
		Name    string   `json:"name"`
		Members []string `json:"members"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		fail(w, 400, err)
		return
	}
	members := append([]string{uid}, req.Members...)
	sort.Strings(members)
	members = uniq(members)
	convID := "g_" + a.msg.ID.Next()
	if err := a.ensureConversation(convID, "group", members, uid); err != nil {
		fail(w, 500, err)
		return
	}
	if _, err := a.db.Exec(`INSERT INTO group_info(group_id, name, owner_uid, created_at) VALUES(?,?,?,?)`,
		convID, req.Name, uid, time.Now().UnixMilli()); err != nil {
		fail(w, 500, err)
		return
	}
	writeJSON(w, 200, map[string]string{"id": convID})
}

func (a *apiv1) ensureConversation(convID, typ string, members []string, owner string) error {
	now := time.Now().UnixMilli()
	if _, err := a.db.Exec(`INSERT IGNORE INTO conversation(conversation_id, type, created_at) VALUES(?,?,?)`,
		convID, typ, now); err != nil {
		return err
	}
	for _, m := range members {
		role := "member"
		if m == owner {
			role = "owner"
		}
		if _, err := a.db.Exec(
			`INSERT IGNORE INTO conversation_member(conversation_id, uid, role, joined_at) VALUES(?,?,?,?)`,
			convID, m, role, now); err != nil {
			return err
		}
	}
	return nil
}

func (a *apiv1) listMembers(w http.ResponseWriter, r *http.Request, uid string) {
	convID := r.PathValue("id")
	ok, err := a.db.IsMember(convID, uid)
	if err != nil {
		fail(w, 500, err)
		return
	}
	if !ok {
		fail(w, 403, errors.New("not a member"))
		return
	}
	rows, err := a.db.Query(`
		SELECT m.uid, u.nickname FROM conversation_member m JOIN user u ON u.uid=m.uid
		WHERE m.conversation_id=?`, convID)
	if err != nil {
		fail(w, 500, err)
		return
	}
	defer rows.Close()
	type member struct {
		UID      string `json:"uid"`
		Nickname string `json:"nickname"`
	}
	out := []member{}
	for rows.Next() {
		var m member
		if err := rows.Scan(&m.UID, &m.Nickname); err != nil {
			fail(w, 500, err)
			return
		}
		out = append(out, m)
	}
	writeJSON(w, 200, out)
}

func (a *apiv1) addMembers(w http.ResponseWriter, r *http.Request, uid string) {
	convID := r.PathValue("id")
	var req struct {
		Members []string `json:"members"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		fail(w, 400, err)
		return
	}
	ok, err := a.db.IsMember(convID, uid)
	if err != nil {
		fail(w, 500, err)
		return
	}
	if !ok {
		fail(w, 403, errors.New("not a member"))
		return
	}
	now := time.Now().UnixMilli()
	for _, m := range req.Members {
		if _, err := a.db.Exec(
			`INSERT IGNORE INTO conversation_member(conversation_id, uid, role, joined_at) VALUES(?,?,?,?)`,
			convID, m, "member", now); err != nil {
			fail(w, 500, err)
			return
		}
	}
	writeJSON(w, 200, map[string]bool{"ok": true})
}

// ---------- 历史 ----------

func (a *apiv1) history(w http.ResponseWriter, r *http.Request, uid string) {
	convID := r.PathValue("id")
	ok, err := a.db.IsMember(convID, uid)
	if err != nil {
		fail(w, 500, err)
		return
	}
	if !ok {
		fail(w, 403, errors.New("not a member"))
		return
	}
	before, _ := strconvParseUint64(r.URL.Query().Get("before_seq"))
	limit := 50
	resp, err := a.msg.Pull(r.Context(), uid, convID, before, uint32(limit))
	if err != nil {
		fail(w, 500, err)
		return
	}
	type histMsg struct {
		ServerMsgID string          `json:"server_msg_id"`
		Seq         uint64          `json:"seq"`
		FromUID     string          `json:"from_uid"`
		MsgType     int             `json:"msg_type"`
		Text        string          `json:"text"`
		Attachment  json.RawMessage `json:"attachment"`
		SentAt      int64           `json:"sent_at"`
	}
	out := []histMsg{}
	for _, m := range resp.Msgs {
		att, _ := json.Marshal(m.Attachment)
		out = append(out, histMsg{
			ServerMsgID: m.ServerMsgId, Seq: m.Seq, FromUID: m.FromUid,
			MsgType: int(m.MsgType), Text: m.Text, Attachment: att, SentAt: m.SentAt,
		})
	}
	writeJSON(w, 200, map[string]any{"msgs": out, "has_more": resp.HasMore, "max_seq": resp.MaxSeq})
}

// ---------- 上传凭证 ----------

// uploadToken 返回 MinIO 预签名 PUT URL（客户端直传），kind: image/file/audio/video
func (a *apiv1) uploadToken(w http.ResponseWriter, r *http.Request, uid string) {
	if a.minio == nil {
		fail(w, 503, errors.New("object storage unavailable"))
		return
	}
	var req struct {
		Kind string `json:"kind"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		fail(w, 400, err)
		return
	}
	switch req.Kind {
	case "image", "file", "audio", "video":
	default:
		req.Kind = "file"
	}
	key, putURL, err := a.minio.PresignPut(req.Kind)
	if err != nil {
		fail(w, 500, err)
		return
	}
	writeJSON(w, 200, map[string]string{"key": key, "put_url": putURL})
}

// download 预签名下载：GET /v1/download?key=...
func (a *apiv1) download(w http.ResponseWriter, r *http.Request, uid string) {
	if a.minio == nil {
		fail(w, 503, errors.New("object storage unavailable"))
		return
	}
	key := r.URL.Query().Get("key")
	if key == "" {
		fail(w, 400, errors.New("missing key"))
		return
	}
	url, err := a.minio.PresignGet(key, 1*time.Hour)
	if err != nil {
		fail(w, 500, err)
		return
	}
	http.Redirect(w, r, url, http.StatusFound)
}

// ---------- local hub stub（logic 进程无长连接，投递走 gateway；M2 引入进程间投递） ----------

func newLocalHub() *hub.Hub { return hub.New() }

// ---------- tiny helpers ----------

func uniq(in []string) []string {
	seen := map[string]struct{}{}
	out := in[:0]
	for _, s := range in {
		if _, ok := seen[s]; !ok {
			seen[s] = struct{}{}
			out = append(out, s)
		}
	}
	return out
}

func strconvParseUint64(s string) (uint64, error) {
	if s == "" {
		return 0, nil
	}
	return strconv.ParseUint(s, 10, 64)
}
