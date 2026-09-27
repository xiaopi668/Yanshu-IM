package main

import (
	"encoding/json"
	"net/http"
	"time"

	"im/internal/config"
)

func fail(w http.ResponseWriter, status int, err error) {
	writeJSON(w, status, map[string]string{"error": err.Error()})
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

// cors 管理后台页面与 API 同源，正常情况下用不到跨源；
// 配置了 IM_ALLOWED_ORIGINS 时才放行白名单来源，未配置时不下发任何 CORS 头。
func cors(cfg *config.Config, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if origin := r.Header.Get("Origin"); origin != "" && len(cfg.AllowedOrigins) > 0 {
			if !cfg.OriginAllowed(origin) {
				w.WriteHeader(http.StatusForbidden)
				return
			}
			w.Header().Set("Access-Control-Allow-Origin", origin)
			w.Header().Add("Vary", "Origin")
			w.Header().Set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
			w.Header().Set("Access-Control-Allow-Headers", "Content-Type, Authorization")
		}
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
