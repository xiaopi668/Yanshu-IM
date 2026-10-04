// Package health 提供存活/就绪探针。
//
// 之前的三个进程都没有探针：进程假死（例如读循环卡住）不会被编排系统发现，
// 反向代理也无从摘流量，只能靠人工看日志。这里统一实现：
// 探针同时 ping MySQL 与 Redis —— 两者任一不可用，进程其实已经无法提供服务。
package health

import (
	"context"
	"database/sql"
	"encoding/json"
	"net/http"
	"time"

	"github.com/redis/go-redis/v9"
)

// Handler 返回 /healthz 处理器。db 不可为 nil；rdb 可为 nil（未接入时跳过）。
func Handler(role string, version string, db *sql.DB, rdb *redis.Client) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		// 探针必须自己限时：数据库僵死时不能让探测请求也一起挂住
		ctx, cancel := context.WithTimeout(r.Context(), 2*time.Second)
		defer cancel()

		body := map[string]any{"ok": true, "role": role, "version": version}
		code := http.StatusOK

		if db != nil {
			if err := db.PingContext(ctx); err != nil {
				body["ok"] = false
				body["mysql"] = err.Error()
				code = http.StatusServiceUnavailable
			}
		}
		if rdb != nil {
			if err := rdb.Ping(ctx).Err(); err != nil {
				body["ok"] = false
				body["redis"] = err.Error()
				code = http.StatusServiceUnavailable
			}
		}

		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(code)
		_ = json.NewEncoder(w).Encode(body)
	}
}
