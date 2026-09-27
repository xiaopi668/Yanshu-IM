package main

// 一期 handler（好友/会话/历史/对象存储）+ 二期（通讯录/朋友圈/归档）

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"sort"
	"strconv"
	"strings"
	"time"

	"im/internal/archive"
	"im/internal/pb"
	"im/internal/store"
)

// ---------- 好友（一期直加接口，二期通讯录走 /contacts） ----------

func (a *apiv1) listFriends(w http.ResponseWriter, r *http.Request, uid string) {
	rows, err := a.db.Query(`
		SELECT f.friend_uid, u.username, u.nickname, u.avatar_url, COALESCE(u.yid,''), f.remark
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
		Yid      string `json:"yid"`
		Remark   string `json:"remark"`
	}
	out := []friend{}
	for rows.Next() {
		var f friend
		if err := rows.Scan(&f.UID, &f.Username, &f.Nickname, &f.Avatar, &f.Yid, &f.Remark); err != nil {
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
		ID      string `json:"id"`
		Type    string `json:"type"`
		Title   string `json:"title"`
		LastSeq uint64 `json:"last_seq"`
		ReadSeq uint64 `json:"read_seq"`
	}
	out := []conv{}
	convIDs := []string{}
	byID := map[string]bool{}
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
		byID[c.ID] = true
		out = append(out, c)
	}
	if err := rows.Err(); err != nil {
		fail(w, 500, err)
		return
	}
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
	_ = byID
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
	// before_seq 向上翻历史（返回 seq < before_seq，升序输出）；缺省从最新往回取。
	// after_seq 向后增量拉取（返回 seq > after_seq），与 WebSocket 的 MsgPullReq 语义一致。
	q := r.URL.Query()
	before, _ := strconv.ParseUint(q.Get("before_seq"), 10, 64)
	after, _ := strconv.ParseUint(q.Get("after_seq"), 10, 64)

	var msgs []*pb.MsgNotify
	var hasMore bool
	if after > 0 {
		resp, err := a.msg.Pull(r.Context(), uid, convID, after, 50)
		if err != nil {
			fail(w, 500, err)
			return
		}
		msgs, hasMore = resp.Msgs, resp.HasMore
	} else {
		var err error
		msgs, hasMore, err = a.msg.PullBefore(r.Context(), uid, convID, before, 50)
		if err != nil {
			fail(w, 500, err)
			return
		}
	}
	maxSeq, err := a.msg.MaxSeq(r.Context(), convID)
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
	for _, m := range msgs {
		att, _ := json.Marshal(m.Attachment)
		out = append(out, histMsg{
			ServerMsgID: m.ServerMsgId, Seq: m.Seq, FromUID: m.FromUid,
			MsgType: int(m.MsgType), Text: m.Text, Attachment: att, SentAt: m.SentAt,
		})
	}
	writeJSON(w, 200, map[string]any{"msgs": out, "has_more": hasMore, "max_seq": maxSeq})
}

// ---------- 对象存储 ----------

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

// ---------- 二期：通讯录 ----------

// contactRequest 按对方 UID 发好友申请
func (a *apiv1) contactRequest(w http.ResponseWriter, r *http.Request, uid string) {
	var req struct {
		ToUID   string `json:"to_uid"`
		Message string `json:"message"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		fail(w, 400, err)
		return
	}
	if req.ToUID == uid {
		fail(w, 400, errors.New("cannot add self"))
		return
	}
	var n int
	if err := a.db.QueryRow(`SELECT COUNT(*) FROM user WHERE uid=?`, req.ToUID).Scan(&n); err != nil || n == 0 {
		fail(w, 404, errors.New("user not found"))
		return
	}
	// 已是好友？
	var m int
	if err := a.db.QueryRow(`SELECT COUNT(*) FROM friend WHERE owner_uid=? AND friend_uid=?`, uid, req.ToUID).Scan(&m); err == nil && m > 0 {
		writeJSON(w, 200, map[string]string{"status": "already_friends"})
		return
	}
	id := a.msg.ID.Next()
	now := time.Now().UnixMilli()
	if _, err := a.db.Exec(
		`INSERT INTO contact_request(id, from_uid, to_uid, message, status, created_at, updated_at)
		 VALUES(?,?,?,?,'pending',?,?)
		 ON DUPLICATE KEY UPDATE message=VALUES(message), status='pending', updated_at=VALUES(updated_at)`,
		id, uid, req.ToUID, req.Message, now, now); err != nil {
		fail(w, 500, err)
		return
	}
	// 实时推送被叫
	a.pushContactEvent(req.ToUID, "request", id, uid, req.Message)
	writeJSON(w, 200, map[string]string{"id": id, "status": "pending"})
}

// contactAccept 通过申请：建立双向好友 + 推送双方
func (a *apiv1) contactAccept(w http.ResponseWriter, r *http.Request, uid string) {
	id := r.PathValue("id")
	var fromUID string
	err := a.db.QueryRow(
		`SELECT from_uid FROM contact_request WHERE id=? AND to_uid=? AND status='pending'`, id, uid).Scan(&fromUID)
	if errors.Is(err, sql.ErrNoRows) {
		fail(w, 404, errors.New("request not found"))
		return
	}
	if err != nil {
		fail(w, 500, err)
		return
	}
	now := time.Now().UnixMilli()
	if _, err := a.db.Exec(`UPDATE contact_request SET status='accepted', updated_at=? WHERE id=?`, now, id); err != nil {
		fail(w, 500, err)
		return
	}
	if _, err := a.db.Exec(
		`INSERT IGNORE INTO friend(owner_uid, friend_uid, created_at) VALUES(?,?,?),(?,?,?)`,
		uid, fromUID, now, fromUID, uid, now); err != nil {
		fail(w, 500, err)
		return
	}
	a.pushContactEvent(fromUID, "accepted", id, uid, "")
	writeJSON(w, 200, map[string]bool{"ok": true})
}

func (a *apiv1) contactReject(w http.ResponseWriter, r *http.Request, uid string) {
	id := r.PathValue("id")
	var fromUID string
	err := a.db.QueryRow(
		`SELECT from_uid FROM contact_request WHERE id=? AND to_uid=? AND status='pending'`, id, uid).Scan(&fromUID)
	if errors.Is(err, sql.ErrNoRows) {
		fail(w, 404, errors.New("request not found"))
		return
	}
	if err != nil {
		fail(w, 500, err)
		return
	}
	if _, err := a.db.Exec(`UPDATE contact_request SET status='rejected', updated_at=? WHERE id=?`, time.Now().UnixMilli(), id); err != nil {
		fail(w, 500, err)
		return
	}
	a.pushContactEvent(fromUID, "rejected", id, uid, "")
	writeJSON(w, 200, map[string]bool{"ok": true})
}

// listContacts 通讯录（好友，按备注/昵称排序）
func (a *apiv1) listContacts(w http.ResponseWriter, r *http.Request, uid string) {
	rows, err := a.db.Query(`
		SELECT f.friend_uid, u.nickname, COALESCE(u.yid,''), f.remark, COALESCE(u.avatar_url,'')
		FROM friend f JOIN user u ON u.uid=f.friend_uid WHERE f.owner_uid=?`, uid)
	if err != nil {
		fail(w, 500, err)
		return
	}
	defer rows.Close()
	type contact struct {
		UID      string `json:"uid"`
		Nickname string `json:"nickname"`
		Yid      string `json:"yid"`
		Remark   string `json:"remark"`
		Avatar   string `json:"avatar"`
	}
	out := []contact{}
	for rows.Next() {
		var c contact
		if err := rows.Scan(&c.UID, &c.Nickname, &c.Yid, &c.Remark, &c.Avatar); err != nil {
			fail(w, 500, err)
			return
		}
		out = append(out, c)
	}
	writeJSON(w, 200, out)
}

func (a *apiv1) listContactRequests(w http.ResponseWriter, r *http.Request, uid string) {
	// 收到的申请（pending）+ 已处理记录
	rows, err := a.db.Query(`
		SELECT c.id, c.from_uid, c.message, c.status, c.created_at, u.nickname, COALESCE(u.yid,'')
		FROM contact_request c JOIN user u ON u.uid=c.from_uid
		WHERE c.to_uid=? ORDER BY c.created_at DESC LIMIT 100`, uid)
	if err != nil {
		fail(w, 500, err)
		return
	}
	defer rows.Close()
	type reqItem struct {
		ID       string `json:"id"`
		FromUID  string `json:"from_uid"`
		Message  string `json:"message"`
		Status   string `json:"status"`
		Created  int64  `json:"created_at"`
		Nickname string `json:"nickname"`
		Yid      string `json:"yid"`
	}
	out := []reqItem{}
	for rows.Next() {
		var it reqItem
		if err := rows.Scan(&it.ID, &it.FromUID, &it.Message, &it.Status, &it.Created, &it.Nickname, &it.Yid); err != nil {
			fail(w, 500, err)
			return
		}
		out = append(out, it)
	}
	writeJSON(w, 200, out)
}

func (a *apiv1) setRemark(w http.ResponseWriter, r *http.Request, uid string) {
	target := r.PathValue("uid")
	var req struct {
		Remark string `json:"remark"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		fail(w, 400, err)
		return
	}
	if _, err := a.db.Exec(`UPDATE friend SET remark=? WHERE owner_uid=? AND friend_uid=?`,
		req.Remark, uid, target); err != nil {
		fail(w, 500, err)
		return
	}
	writeJSON(w, 200, map[string]bool{"ok": true})
}

func (a *apiv1) removeContact(w http.ResponseWriter, r *http.Request, uid string) {
	target := r.PathValue("uid")
	if _, err := a.db.Exec(`DELETE FROM friend WHERE (owner_uid=? AND friend_uid=?) OR (owner_uid=? AND friend_uid=?)`,
		uid, target, target, uid); err != nil {
		fail(w, 500, err)
		return
	}
	writeJSON(w, 200, map[string]bool{"ok": true})
}

// pushContactEvent 通过 gateway hub 实时推送通讯录事件
func (a *apiv1) pushContactEvent(toUID, typ, reqID, fromUID, message string) {
	nick, yid := "", ""
	_ = a.db.QueryRow(`SELECT nickname, COALESCE(yid,'') FROM user WHERE uid=?`, fromUID).Scan(&nick, &yid)
	a.hub.SendToUser(toUID, a.msg.BuildContactEvent(typ, reqID, fromUID, yid, nick, message))
}

// ---------- 二期：朋友圈 ----------

type momentItem struct {
	ID        string            `json:"id"`
	UID       string            `json:"uid"`
	Nickname  string            `json:"nickname"`
	Text      string            `json:"text"`
	ImageKeys []string          `json:"images"`
	Created   int64             `json:"created_at"`
	Likes     int               `json:"likes"`
	LikedByMe bool              `json:"liked_by_me"`
	Comments  []momentCommentVO `json:"comments"`
}

type momentCommentVO struct {
	ID       string `json:"id"`
	UID      string `json:"uid"`
	Nickname string `json:"nickname"`
	Text     string `json:"text"`
	Created  int64  `json:"created_at"`
}

func (a *apiv1) createMoment(w http.ResponseWriter, r *http.Request, uid string) {
	var req struct {
		Text   string   `json:"text"`
		Images []string `json:"images"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		fail(w, 400, err)
		return
	}
	if strings.TrimSpace(req.Text) == "" && len(req.Images) == 0 {
		fail(w, 400, errors.New("empty moment"))
		return
	}
	id := a.msg.ID.Next()
	imgs, _ := json.Marshal(req.Images)
	if _, err := a.db.Exec(`INSERT INTO moment(id, uid, text, images, created_at) VALUES(?,?,?,?,?)`,
		id, uid, req.Text, string(imgs), time.Now().UnixMilli()); err != nil {
		fail(w, 500, err)
		return
	}
	writeJSON(w, 200, map[string]string{"id": id})
}

// momentFeed 自己 + 好友的动态（时间倒序，按 before_id 分页）
func (a *apiv1) momentFeed(w http.ResponseWriter, r *http.Request, uid string) {
	a.momentsFor(w, r, uid, true)
}

func (a *apiv1) momentMine(w http.ResponseWriter, r *http.Request, uid string) {
	a.momentsFor(w, r, uid, false)
}

func (a *apiv1) momentsFor(w http.ResponseWriter, r *http.Request, uid string, friendsOnly bool) {
	before, _ := strconv.ParseUint(r.URL.Query().Get("before_id"), 10, 64)
	limit := 20

	q := `SELECT m.id, m.uid, u.nickname, COALESCE(m.text,''), COALESCE(m.images,'[]'), m.created_at
		FROM moment m JOIN user u ON u.uid=m.uid WHERE 1=1`
	args := []any{}
	if friendsOnly {
		q += ` AND m.uid IN (SELECT friend_uid FROM friend WHERE owner_uid=? UNION SELECT ?)`
		args = append(args, uid, uid)
	} else {
		q += ` AND m.uid=?`
		args = append(args, uid)
	}
	if before > 0 {
		q += ` AND m.id<?`
		args = append(args, strconv.FormatUint(before, 10))
	}
	q += ` ORDER BY m.id DESC LIMIT ?`
	args = append(args, limit)

	rows, err := a.db.Query(q, args...)
	if err != nil {
		fail(w, 500, err)
		return
	}
	defer rows.Close()
	out := []momentItem{}
	for rows.Next() {
		var it momentItem
		var imgs string
		if err := rows.Scan(&it.ID, &it.UID, &it.Nickname, &it.Text, &imgs, &it.Created); err != nil {
			fail(w, 500, err)
			return
		}
		_ = json.Unmarshal([]byte(imgs), &it.ImageKeys)
		it.Comments = []momentCommentVO{}
		out = append(out, it)
	}
	if err := rows.Err(); err != nil {
		fail(w, 500, err)
		return
	}
	// 批量取点赞与评论
	for i := range out {
		out[i].Likes, out[i].LikedByMe = a.likeSummary(out[i].ID, uid)
		out[i].Comments = a.commentsOf(out[i].ID)
	}
	writeJSON(w, 200, out)
}

func (a *apiv1) likeSummary(momentID, viewer string) (int, bool) {
	var n int
	if err := a.db.QueryRow(`SELECT COUNT(*) FROM moment_like WHERE moment_id=?`, momentID).Scan(&n); err != nil {
		return 0, false
	}
	var me int
	_ = a.db.QueryRow(`SELECT COUNT(*) FROM moment_like WHERE moment_id=? AND uid=?`, momentID, viewer).Scan(&me)
	return n, me > 0
}

func (a *apiv1) commentsOf(momentID string) []momentCommentVO {
	rows, err := a.db.Query(`
		SELECT c.id, c.uid, u.nickname, c.text, c.created_at
		FROM moment_comment c JOIN user u ON u.uid=c.uid
		WHERE c.moment_id=? ORDER BY c.created_at ASC`, momentID)
	if err != nil {
		return []momentCommentVO{}
	}
	defer rows.Close()
	out := []momentCommentVO{}
	for rows.Next() {
		var c momentCommentVO
		if err := rows.Scan(&c.ID, &c.UID, &c.Nickname, &c.Text, &c.Created); err != nil {
			continue
		}
		out = append(out, c)
	}
	return out
}

func (a *apiv1) momentLike(w http.ResponseWriter, r *http.Request, uid string) {
	id := r.PathValue("id")
	if _, err := a.db.Exec(`INSERT IGNORE INTO moment_like(moment_id, uid, created_at) VALUES(?,?,?)`,
		id, uid, time.Now().UnixMilli()); err != nil {
		fail(w, 500, err)
		return
	}
	writeJSON(w, 200, map[string]bool{"ok": true})
}

func (a *apiv1) momentUnlike(w http.ResponseWriter, r *http.Request, uid string) {
	id := r.PathValue("id")
	if _, err := a.db.Exec(`DELETE FROM moment_like WHERE moment_id=? AND uid=?`, id, uid); err != nil {
		fail(w, 500, err)
		return
	}
	writeJSON(w, 200, map[string]bool{"ok": true})
}

func (a *apiv1) momentComment(w http.ResponseWriter, r *http.Request, uid string) {
	id := r.PathValue("id")
	var req struct {
		Text string `json:"text"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		fail(w, 400, err)
		return
	}
	if strings.TrimSpace(req.Text) == "" {
		fail(w, 400, errors.New("empty comment"))
		return
	}
	cid := a.msg.ID.Next()
	if _, err := a.db.Exec(`INSERT INTO moment_comment(id, moment_id, uid, text, created_at) VALUES(?,?,?,?,?)`,
		cid, id, uid, req.Text, time.Now().UnixMilli()); err != nil {
		fail(w, 500, err)
		return
	}
	writeJSON(w, 200, map[string]string{"id": cid})
}

func (a *apiv1) deleteMoment(w http.ResponseWriter, r *http.Request, uid string) {
	id := r.PathValue("id")
	if _, err := a.db.Exec(`DELETE FROM moment WHERE id=? AND uid=?`, id, uid); err != nil {
		fail(w, 500, err)
		return
	}
	_, _ = a.db.Exec(`DELETE FROM moment_like WHERE moment_id=?`, id)
	_, _ = a.db.Exec(`DELETE FROM moment_comment WHERE moment_id=?`, id)
	writeJSON(w, 200, map[string]bool{"ok": true})
}

// ---------- 聊天记录归档（每日 03:00 归档前一天，写入对象存储） ----------

func (a *apiv1) startArchiver() {
	if a.minio == nil {
		return
	}
	go func() {
		for {
			now := time.Now()
			next := time.Date(now.Year(), now.Month(), now.Day(), 3, 0, 0, 0, now.Location())
			if !next.After(now) {
				next = next.Add(24 * time.Hour)
			}
			time.Sleep(next.Sub(now))
			day := now.AddDate(0, 0, -1).Format("20060102")
			archive.ArchiveDay(a.db.DB, a.minio, day)
		}
	}()
}

func uniq(in []string) []string {
	seen := map[string]struct{}{}
	out := make([]string, 0, len(in))
	for _, s := range in {
		if _, ok := seen[s]; !ok {
			seen[s] = struct{}{}
			out = append(out, s)
		}
	}
	return out
}

// ---------- 三期：全局消息搜索 ----------

func (a *apiv1) searchMessages(w http.ResponseWriter, r *http.Request, uid string) {
	q := strings.TrimSpace(r.URL.Query().Get("q"))
	if len(q) < 1 {
		writeJSON(w, 200, []any{})
		return
	}
	rows, err := a.db.Query(`
		SELECT m.server_msg_id, m.conversation_id, m.seq, m.from_uid, m.text, m.sent_at
		FROM message m
		JOIN conversation_member cm ON cm.conversation_id=m.conversation_id AND cm.uid=?
		WHERE m.text LIKE ? AND m.msg_type=0
		ORDER BY m.sent_at DESC LIMIT 50`, uid, store.LikeQuery(q))
	if err != nil {
		fail(w, 500, err)
		return
	}
	defer rows.Close()
	type hit struct {
		ServerMsgID string `json:"server_msg_id"`
		ConvID      string `json:"conversation_id"`
		Seq         uint64 `json:"seq"`
		FromUID     string `json:"from_uid"`
		Text        string `json:"text"`
		SentAt      int64  `json:"sent_at"`
	}
	out := []hit{}
	for rows.Next() {
		var h hit
		if err := rows.Scan(&h.ServerMsgID, &h.ConvID, &h.Seq, &h.FromUID, &h.Text, &h.SentAt); err != nil {
			continue
		}
		out = append(out, h)
	}
	writeJSON(w, 200, out)
}

// ---------- 三期：群管理 ----------

// groupRole 返回 uid 在群里的角色（"" = 不是成员）
func (a *apiv1) groupRole(convID, uid string) (string, error) {
	var role string
	err := a.db.QueryRow(
		`SELECT role FROM conversation_member WHERE conversation_id=? AND uid=?`, convID, uid).Scan(&role)
	if errors.Is(err, sql.ErrNoRows) {
		return "", errors.New("not a member")
	}
	return role, err
}

// setAnnouncement 仅 owner/admin
func (a *apiv1) setAnnouncement(w http.ResponseWriter, r *http.Request, uid string) {
	convID := r.PathValue("id")
	role, err := a.groupRole(convID, uid)
	if err != nil {
		fail(w, 403, err)
		return
	}
	if role != "owner" && role != "admin" {
		fail(w, 403, errors.New("only owner/admin"))
		return
	}
	var req struct {
		Announcement string `json:"announcement"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		fail(w, 400, err)
		return
	}
	if _, err := a.db.Exec(`UPDATE group_info SET announcement=? WHERE group_id=?`, req.Announcement, convID); err != nil {
		fail(w, 500, err)
		return
	}
	writeJSON(w, 200, map[string]bool{"ok": true})
}

// groupInfo 群信息（含公告），成员可见
func (a *apiv1) getGroupInfo(w http.ResponseWriter, r *http.Request, uid string) {
	convID := r.PathValue("id")
	if _, err := a.groupRole(convID, uid); err != nil {
		fail(w, 403, err)
		return
	}
	var name, owner, announcement string
	err := a.db.QueryRow(
		`SELECT name, owner_uid, COALESCE(announcement,'') FROM group_info WHERE group_id=?`, convID).
		Scan(&name, &owner, &announcement)
	if errors.Is(err, sql.ErrNoRows) {
		fail(w, 404, errors.New("group not found"))
		return
	}
	if err != nil {
		fail(w, 500, err)
		return
	}
	writeJSON(w, 200, map[string]string{"id": convID, "name": name, "owner_uid": owner, "announcement": announcement})
}

// kickMember 踢人：owner/admin 可踢，不能踢 owner
func (a *apiv1) kickMember(w http.ResponseWriter, r *http.Request, uid string) {
	convID := r.PathValue("id")
	target := r.PathValue("uid")
	role, err := a.groupRole(convID, uid)
	if err != nil {
		fail(w, 403, err)
		return
	}
	if role != "owner" && role != "admin" {
		fail(w, 403, errors.New("only owner/admin"))
		return
	}
	targetRole, err := a.groupRole(convID, target)
	if err != nil {
		fail(w, 404, err)
		return
	}
	if targetRole == "owner" {
		fail(w, 403, errors.New("cannot kick owner"))
		return
	}
	if _, err := a.db.Exec(`DELETE FROM conversation_member WHERE conversation_id=? AND uid=?`, convID, target); err != nil {
		fail(w, 500, err)
		return
	}
	a.sendGroupSystemMsg(convID, uid, map[string]any{"type": "member_kicked", "uid": target})
	writeJSON(w, 200, map[string]bool{"ok": true})
}

// setGroupRole 设角色：仅 owner；可设 admin / member
func (a *apiv1) setGroupRole(w http.ResponseWriter, r *http.Request, uid string) {
	convID := r.PathValue("id")
	role, err := a.groupRole(convID, uid)
	if err != nil {
		fail(w, 403, err)
		return
	}
	if role != "owner" {
		fail(w, 403, errors.New("only owner"))
		return
	}
	var req struct {
		UID  string `json:"uid"`
		Role string `json:"role"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		fail(w, 400, err)
		return
	}
	if req.Role != "admin" && req.Role != "member" {
		fail(w, 400, errors.New("role must be admin/member"))
		return
	}
	if _, err := a.db.Exec(`UPDATE conversation_member SET role=? WHERE conversation_id=? AND uid=?`,
		req.Role, convID, req.UID); err != nil {
		fail(w, 500, err)
		return
	}
	writeJSON(w, 200, map[string]bool{"ok": true})
}

// addMembers 增强：拉人后发系统消息（沿用原接口路径，覆盖实现）
func (a *apiv1) addMembersEnhanced(w http.ResponseWriter, r *http.Request, uid string) {
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
	var req struct {
		Members []string `json:"members"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		fail(w, 400, err)
		return
	}
	now := time.Now().UnixMilli()
	for _, m := range req.Members {
		res, err := a.db.Exec(
			`INSERT IGNORE INTO conversation_member(conversation_id, uid, role, joined_at) VALUES(?,?,?,?)`,
			convID, m, "member", now)
		if err != nil {
			fail(w, 500, err)
			return
		}
		aff, _ := res.RowsAffected()
		if aff > 0 {
			a.sendGroupSystemMsg(convID, uid, map[string]any{"type": "member_added", "uid": m, "by": uid})
		}
	}
	writeJSON(w, 200, map[string]bool{"ok": true})
}

// sendGroupSystemMsg 往群会话发一条系统消息（走标准 seq/落库/投递）。
// 调用方可能已返回，因此用独立的超时 context，不能挂在请求 context 上。
func (a *apiv1) sendGroupSystemMsg(convID, fromUID string, payload map[string]any) {
	b, _ := json.Marshal(payload)
	send := &pb.MsgSend{
		ClientMsgId:    "sys-" + a.msg.ID.Next(),
		ConversationId: convID,
		MsgType:        pb.MsgType_MSG_SYSTEM,
		Text:           string(b),
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_, _, _ = a.msg.Send(ctx, fromUID, send)
}

// ---------- 三期：头像 ----------

func (a *apiv1) setAvatar(w http.ResponseWriter, r *http.Request, uid string) {
	var req struct {
		Key string `json:"key"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		fail(w, 400, err)
		return
	}
	if req.Key == "" {
		fail(w, 400, errors.New("key required"))
		return
	}
	if _, err := a.db.Exec(`UPDATE user SET avatar_url=? WHERE uid=?`, req.Key, uid); err != nil {
		fail(w, 500, err)
		return
	}
	writeJSON(w, 200, map[string]bool{"ok": true})
}
