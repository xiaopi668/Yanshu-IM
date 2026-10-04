// 附件下载票据与对象级授权。
//
// 背景（P0 修复）：旧实现把 7 天有效的登录 JWT 拼进 "/v1/download?key=...&token=..."
// 并写进消息体的 Attachment.Url，随 MsgNotify 广播给会话全部成员、落库永久保存。
// 任何拿到消息的人（或拉到聊天归档的人）都能冒出消息发送者调用全部 API。
//
// 现在改成两段式：
//  1. 消息里只存对象 key；
//  2. 取用时调用方用「自己的」登录态换一张短时票据，票据与单个 key 绑定、HMAC 签名、10 分钟过期。
//
// 于是即使票据泄露，也既不能换成登录凭证，也不能用于其它对象，且很快失效。
package main

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"net/http"
	"strconv"
	"strings"
	"time"

	"im/internal/storage"
)

// ticketTTL 票据有效期。取图/下载是即时动作，10 分钟足够，且明显短于一次会话。
const ticketTTL = 10 * time.Minute

// ticketSecret 由 JWT 密钥派生，用途隔离：即使票据被伪造也不等于能签 token。
func (a *apiv1) ticketSecret() []byte {
	return []byte("im-attachment-ticket|" + a.cfg.JWTSecret)
}

func (a *apiv1) ticketMAC(key, exp string) string {
	m := hmac.New(sha256.New, a.ticketSecret())
	_, _ = m.Write([]byte(key + "|" + exp))
	return hex.EncodeToString(m.Sum(nil))
}

// makeTicket 生成 "<过期毫秒>.<hmac>"，与 key 绑定
func (a *apiv1) makeTicket(key string) string {
	exp := strconv.FormatInt(time.Now().Add(ticketTTL).UnixMilli(), 10)
	return exp + "." + a.ticketMAC(key, exp)
}

// checkTicket 校验票据：格式、签名（恒定时间比较）、过期
func (a *apiv1) checkTicket(key, ticket string) error {
	exp, mac, ok := strings.Cut(ticket, ".")
	if !ok {
		return errors.New("下载票据格式非法")
	}
	// hmac.Equal 恒定时间比较，避免按字节猜测签名
	if !hmac.Equal([]byte(mac), []byte(a.ticketMAC(key, exp))) {
		return errors.New("下载票据签名不匹配")
	}
	ms, err := strconv.ParseInt(exp, 10, 64)
	if err != nil {
		return errors.New("下载票据过期时间非法")
	}
	if time.Now().UnixMilli() > ms {
		return errors.New("下载票据已过期")
	}
	return nil
}

// canViewObject 对象级授权：调用方是否被允许读取该 key。
// 覆盖当前会引用对象的三种业务：会话消息附件、用户头像、朋友圈图片。
func (a *apiv1) canViewObject(uid, key string) (bool, error) {
	var n int
	// 1) 会话消息附件：调用方必须是该会话成员
	if err := a.db.QueryRow(
		`SELECT COUNT(*) FROM message m
		 JOIN conversation_member cm ON cm.conversation_id = m.conversation_id AND cm.uid = ?
		 WHERE m.attachment_key = ?`, uid, key).Scan(&n); err != nil {
		return false, err
	}
	if n > 0 {
		return true, nil
	}
	// 2) 头像：站内可见（通讯录/朋友圈/搜索结果都会展示别人的头像）
	if err := a.db.QueryRow(`SELECT COUNT(*) FROM user WHERE avatar_url = ?`, key).Scan(&n); err != nil {
		return false, err
	}
	if n > 0 {
		return true, nil
	}
	// 3) 朋友圈图片：仅作者本人与其好友可见（与 feed 的可见性一致）。
	//    key 字符集为 [a-z0-9/]，不含 LIKE 通配符，可安全用于子串匹配。
	if err := a.db.QueryRow(
		`SELECT COUNT(*) FROM moment mo
		 WHERE mo.images LIKE ?
		   AND (mo.uid = ? OR mo.uid IN (SELECT friend_uid FROM friend WHERE owner_uid = ?))`,
		"%"+key+"%", uid, uid).Scan(&n); err != nil {
		return false, err
	}
	return n > 0, nil
}

// attachmentTicket 用登录态换一张短时下载票据。
// 返回的 url 可直接 GET（浏览器/系统下载器可用），不再携带登录 JWT。
func (a *apiv1) attachmentTicket(w http.ResponseWriter, r *http.Request, uid string) {
	var req struct {
		Key string `json:"key"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		fail(w, 400, err)
		return
	}
	key := strings.TrimSpace(req.Key)
	if !storage.ValidObjectKey(key) {
		fail(w, 400, errors.New("key 非法"))
		return
	}
	if a.objects.Get() == nil {
		fail(w, 503, errors.New("object storage unavailable"))
		return
	}
	ok, err := a.canViewObject(uid, key)
	if err != nil {
		fail(w, 500, err)
		return
	}
	if !ok {
		// 不区分「不存在」与「无权限」，避免把 key 是否存在变成一个探测接口
		fail(w, 403, errors.New("无权访问该对象"))
		return
	}
	writeJSON(w, 200, map[string]string{
		"url": a.baseURL(r) + "/v1/download?key=" + key + "&ticket=" + a.makeTicket(key),
	})
}
