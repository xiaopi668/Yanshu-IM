// Package migrate 幂等迁移：为已存在的旧库补列/补表（新部署直接由 schema.sql 建全）。
// 通过 MySQL 命名锁串行化，避免 logic / admin 多个进程同时 ALTER 同一张表。
package migrate

import (
	"crypto/sha1"
	"database/sql"
	"encoding/hex"
	"errors"
	"log"
	"time"

	"github.com/go-sql-driver/mysql"
)

var statements = []string{
	// 旧库 user 表补雁书号相关列（新部署 user 表直接含 yid）
	`ALTER TABLE user ADD COLUMN yid VARCHAR(32) NULL`,
	`ALTER TABLE user ADD UNIQUE KEY uk_yid (yid)`,
	`ALTER TABLE user ADD COLUMN yid_changed TINYINT NOT NULL DEFAULT 0`,
	// 为旧用户回填雁书号（username 即默认号）
	`UPDATE user SET yid = username WHERE yid IS NULL`,
	// 群公告
	`ALTER TABLE group_info ADD COLUMN announcement MEDIUMTEXT NULL`,
	// 用户邮箱
	`ALTER TABLE user ADD COLUMN email VARCHAR(128) NULL`,
	// 存量 OIDC 账号自愈：早期建号时误把 yid_changed 置 1，导致这些账号永远改不了雁书号。
	// yid = username 说明号还是自动生成的（用户没动过），放开这一次机会。
	// 普通注册用户的 yid_changed 本来就是 0，且 yid 是随机 ys 号 ≠ username，不受影响。
	`UPDATE user SET yid_changed = 0 WHERE yid = username AND yid IS NOT NULL AND yid <> ''`,
	// 附件对象 key 独立成列 + 索引：下载授权需要按 key 反查会话成员关系
	`ALTER TABLE message ADD COLUMN attachment_key VARCHAR(160) NULL`,
	`ALTER TABLE message ADD KEY idx_attachment_key (attachment_key)`,
	`ALTER TABLE user ADD KEY idx_avatar_url (avatar_url(191))`,
	// 归档与统计按时间范围扫描，缺索引会全表扫
	`ALTER TABLE message ADD KEY idx_sent_at (sent_at)`,
	// 存量消息回填 attachment_key：旧客户端把整条 "/v1/download?key=..&token=.." 存进了 attachment.url，
	// 这里尽力抠出 key。抠不出来也无所谓 —— 授权判定失败就是 403，与留空等价，
	// 但注意旧消息里的 token 已经随消息广播出去，只能靠改密/轮换密钥止损，无法追溯收回。
	`UPDATE message SET attachment_key = SUBSTRING_INDEX(SUBSTRING_INDEX(SUBSTRING_INDEX(attachment,'key=',-1),'&',1),'"',1)
	  WHERE attachment_key IS NULL AND attachment LIKE '%/v1/download?key=%'`,
	// 令牌版本：支持「改密/重置/封禁后旧 JWT 立即失效」
	`ALTER TABLE user_state ADD COLUMN token_version BIGINT NOT NULL DEFAULT 0`,
}

const lockName = "im:schema_migrate"

// Run 逐条执行；已执行过的语句记录在 schema_migrations，"已存在"类错误同样视为成功。
func Run(db *sql.DB) {
	if _, err := db.Exec(
		`CREATE TABLE IF NOT EXISTS schema_migrations(
			id VARCHAR(40) PRIMARY KEY,
			applied_at BIGINT NOT NULL
		)`); err != nil {
		log.Printf("[migrate] 建 schema_migrations 失败: %v", err)
	}

	// 多进程同时启动时串行化，避免并发 ALTER 冲突
	locked := grabLock(db)
	defer releaseLock(db, locked)

	for _, q := range statements {
		id := statementID(q)
		var one int
		if err := db.QueryRow(`SELECT 1 FROM schema_migrations WHERE id=?`, id).Scan(&one); err == nil {
			continue
		}
		if _, err := db.Exec(q); err != nil {
			if !alreadyApplied(err) {
				log.Printf("[migrate] %s: %v", q, err)
				continue
			}
			// 语句本身不幂等但效果已存在（列/键已建），照样记账
		}
		if _, err := db.Exec(
			`INSERT INTO schema_migrations(id, applied_at) VALUES(?,?) ON DUPLICATE KEY UPDATE id=id`,
			id, time.Now().UnixMilli()); err != nil {
			log.Printf("[migrate] 记账失败 %s: %v", id, err)
		}
	}
	log.Println("[migrate] done")
}

// grabLock 取 MySQL 命名锁；取不到（30s 超时）时返回 false，调用方仍继续执行
func grabLock(db *sql.DB) bool {
	var got sql.NullInt64
	if err := db.QueryRow(`SELECT GET_LOCK(?, 30)`, lockName).Scan(&got); err != nil {
		log.Printf("[migrate] 取锁失败: %v", err)
		return false
	}
	return got.Valid && got.Int64 == 1
}

func releaseLock(db *sql.DB, held bool) {
	if !held {
		return
	}
	_, _ = db.Exec(`SELECT RELEASE_LOCK(?)`, lockName)
}

// alreadyApplied 判断是否为"重复列/重复键/重复数据"这类已生效错误。
// 用 MySQL 错误码而非字符串匹配，避免驱动或服务器文案变化导致失效。
func alreadyApplied(err error) bool {
	var me *mysql.MySQLError
	if !errors.As(err, &me) {
		return false
	}
	switch me.Number {
	case 1060, // Duplicate column name
		1061, // Duplicate key name
		1062, // Duplicate entry（回填 yid 撞唯一键）
		1091: // Can't DROP; check that column/key exists
		return true
	}
	return false
}

func statementID(q string) string {
	sum := sha1.Sum([]byte(q))
	return hex.EncodeToString(sum[:])[:40]
}
