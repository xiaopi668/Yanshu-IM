// Package migrate 幂等迁移：为已存在的旧库补列/补表（新部署直接由 schema.sql 建全）。
package migrate

import (
	"database/sql"
	"log"
	"strings"
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
}

// Run 逐条执行，忽略"已存在"类错误。
func Run(db *sql.DB) {
	for _, q := range statements {
		if _, err := db.Exec(q); err != nil {
			s := err.Error()
			if strings.Contains(s, "1060") || strings.Contains(s, "1061") ||
				strings.Contains(s, "Duplicate column") || strings.Contains(s, "Duplicate key") {
				continue
			}
			log.Printf("[migrate] %s: %v", q, err)
		}
	}
	log.Println("[migrate] done")
}
