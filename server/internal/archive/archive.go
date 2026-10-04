// Package archive 聊天记录归档：按天把消息导出为 JSONL 写入对象存储（S3/MinIO）。
package archive

import (
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"strconv"
	"strings"
	"time"

	"im/internal/storage"
)

// ArchiveDay 归档指定日期的全部消息，返回归档条数
func ArchiveDay(db *sql.DB, store *storage.ObjectStore, day string) (int, error) {
	if store == nil {
		return 0, nil
	}
	rows, err := db.Query(`
		SELECT conversation_id, COUNT(*) FROM message
		WHERE sent_at >= ? AND sent_at < ? GROUP BY conversation_id`,
		dayStart(day), dayStart(next(day)))
	if err != nil {
		return 0, err
	}
	type pair struct {
		convID string
		count  int64
	}
	var pairs []pair
	for rows.Next() {
		var p pair
		if err := rows.Scan(&p.convID, &p.count); err == nil {
			pairs = append(pairs, p)
		}
	}
	rows.Close()

	total := 0
	for _, p := range pairs {
		data, err := exportConv(db, p.convID, dayStart(day), dayStart(next(day)))
		if err != nil {
			continue
		}
		// key 用随机串而不是 <会话ID>：归档对象是整段聊天明文，
		// 一旦对象存储被列举或 key 被推导，等于全站历史泄露。
		// 具体 key 记录在 archive_log.object_key，管理后台照旧可查。
		key := "archive/" + day + "/" + randToken() + ".jsonl"
		if err := store.Upload(key, data); err != nil {
			continue
		}
		_, _ = db.Exec(
			`INSERT IGNORE INTO archive_log(day, conversation_id, count, object_key, created_at) VALUES(?,?,?,?,?)`,
			day, p.convID, p.count, key, time.Now().UnixMilli())
		total += int(p.count)
	}
	return total, nil
}

func exportConv(db *sql.DB, convID string, from, to int64) ([]byte, error) {
	rows, err := db.Query(`
		SELECT server_msg_id, seq, from_uid, msg_type, text, attachment, sent_at FROM message
		WHERE conversation_id=? AND sent_at>=? AND sent_at<? ORDER BY seq`,
		convID, from, to)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var b strings.Builder
	for rows.Next() {
		var msgID, fromUID, text, att string
		var seq, msgType, sentAt int64
		if err := rows.Scan(&msgID, &seq, &fromUID, &msgType, &text, &att, &sentAt); err != nil {
			continue
		}
		line, _ := json.Marshal(map[string]any{
			"id": msgID, "seq": seq, "from": fromUID, "type": msgType,
			"text": text, "attachment": json.RawMessage(att), "sent_at": sentAt,
		})
		b.Write(line)
		b.WriteByte('\n')
	}
	return []byte(b.String()), nil
}

// randToken 16 字节随机 hex，作为归档对象的不可推导文件名
func randToken() string {
	b := make([]byte, 16)
	if _, err := rand.Read(b); err != nil {
		// 取不到随机数时退化为时间戳，宁可文件名可预测也不要写失败
		return strconv.FormatInt(time.Now().UnixNano(), 16)
	}
	return hex.EncodeToString(b)
}

func dayStart(day string) int64 {
	t, err := time.ParseInLocation("20060102", day, time.Local)
	if err != nil {
		return 0
	}
	return t.UnixMilli()
}

func next(day string) string {
	t, _ := time.ParseInLocation("20060102", day, time.Local)
	return t.AddDate(0, 0, 1).Format("20060102")
}
