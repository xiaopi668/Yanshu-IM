package main

import (
	"context"
	"encoding/json"
	"net/http"
	"time"

	"github.com/redis/go-redis/v9"
)

func fail(w http.ResponseWriter, status int, err error) {
	writeJSON(w, status, map[string]string{"error": err.Error()})
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

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

func dayStart(day string) int64 {
	t, err := time.ParseInLocation("20060102", day, time.Local)
	if err != nil {
		return 0
	}
	return t.UnixMilli()
}

// reportOnline gateway 周期上报在线数到 Redis（供 admin 统计）
func reportOnline(ctx context.Context, rdb *redis.Client, count int64) {
	_ = rdb.Set(ctx, "im:online", count, 2*time.Minute).Err()
}
