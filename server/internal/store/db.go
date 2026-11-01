package store

import (
	"database/sql"
	"fmt"
	"strings"
)

// ErrNoRows 转发 database/sql 的哨兵错误
var ErrNoRows = sql.ErrNoRows

// DB 对 *sql.DB 的轻封装，附加项目常用查询
type DB struct {
	*sql.DB
}

func Wrap(db *sql.DB) *DB { return &DB{db} }

func (d *DB) IsMember(convID, uid string) (bool, error) {
	var n int
	err := d.QueryRow(`SELECT COUNT(*) FROM conversation_member WHERE conversation_id=? AND uid=?`,
		convID, uid).Scan(&n)
	return n > 0, err
}

// MembersOf 批量取多个会话的成员列表，key 为 conversation_id
func (d *DB) MembersOf(convIDs []string) (map[string][]string, error) {
	q := fmt.Sprintf(
		`SELECT conversation_id, uid FROM conversation_member WHERE conversation_id IN (%s)`,
		placeholders(len(convIDs)))
	args := make([]any, len(convIDs))
	for i, id := range convIDs {
		args[i] = id
	}
	rows, err := d.Query(q, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[string][]string{}
	for rows.Next() {
		var cid, uid string
		if err := rows.Scan(&cid, &uid); err != nil {
			return nil, err
		}
		out[cid] = append(out[cid], uid)
	}
	return out, rows.Err()
}

// NicknamesOf 批量取用户昵称，key 为 uid
func (d *DB) NicknamesOf(uids []string) (map[string]string, error) {
	if len(uids) == 0 {
		return map[string]string{}, nil
	}
	q := fmt.Sprintf(`SELECT uid, nickname FROM user WHERE uid IN (%s)`, placeholders(len(uids)))
	args := make([]any, len(uids))
	for i, id := range uids {
		args[i] = id
	}
	rows, err := d.Query(q, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[string]string{}
	for rows.Next() {
		var uid, nick string
		if err := rows.Scan(&uid, &nick); err != nil {
			return nil, err
		}
		out[uid] = nick
	}
	return out, rows.Err()
}

func placeholders(n int) string {
	return strings.TrimSuffix(strings.Repeat("?,", n), ",")
}

// RemarksOf 批量取当前用户对某些好友设置的备注（没有设置的不出现在结果里）。
//
// 用户反馈：通讯录里改了备注，聊天页标题不跟着变 —— 因为会话列表只取了昵称。
// 备注是「我这边的称呼」，理应优先于对方昵称。
func (d *DB) RemarksOf(ownerUID string, friendUIDs []string) (map[string]string, error) {
	out := map[string]string{}
	if ownerUID == "" || len(friendUIDs) == 0 {
		return out, nil
	}
	q := fmt.Sprintf(
		`SELECT friend_uid, remark FROM friend WHERE owner_uid=? AND friend_uid IN (%s)`,
		placeholders(len(friendUIDs)),
	)
	args := make([]any, 0, len(friendUIDs)+1)
	args = append(args, ownerUID)
	for _, id := range friendUIDs {
		args = append(args, id)
	}
	rows, err := d.Query(q, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	for rows.Next() {
		var uid, remark string
		if err := rows.Scan(&uid, &remark); err != nil {
			return nil, err
		}
		if remark != "" {
			out[uid] = remark
		}
	}
	return out, rows.Err()
}
