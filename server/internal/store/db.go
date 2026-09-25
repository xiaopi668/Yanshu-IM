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
