package config

import (
	"errors"
	"fmt"
	"log"
	"os"
	"strconv"
	"strings"
)

type Config struct {
	// MySQL DSN，如 "user:pass@tcp(127.0.0.1:3306)/im?parseTime=true"
	MySQLDSN string
	// Redis 地址，如 "127.0.0.1:6379"
	RedisAddr string
	RedisPass string
	// JWT 签名密钥
	JWTSecret string
	// gateway WebSocket 监听地址
	GatewayAddr string
	// logic HTTP 监听地址
	LogicAddr string
	// MinIO / S3 兼容对象存储（IM_STORAGE_*）
	StorageEndpoint  string
	StorageAccessKey string
	StorageSecretKey string
	StorageBucket    string
	StorageSecure    bool
	StorageRegion    string // S3 兼容实现需要（如 us-east-1）
	StoragePathStyle bool   // MinIO/R2 用 path-style，AWS S3 用 virtual-host
	// 管理后台
	AdminToken string
	AdminAddr  string
	// LiveKit
	LiveKitHost      string // wss://... 客户端连接地址
	LiveKitAPIKey    string
	LiveKitAPISecret string
	// 站点对外地址（如 https://im.example.com）。
	// 设置后为权威值：OIDC redirect_uri 校验以它为准，忽略 X-Forwarded-Host。
	PublicBaseURL string
	// 允许的跨源来源（逗号分隔）。为空时 CORS 放行 *、WS 校验回落到"同 Host 或无 Origin"。
	AllowedOrigins []string
	// 是否信任反向代理的 X-Forwarded-For / X-Forwarded-Host（默认 false，防止伪造客户端 IP）
	TrustProxy bool
	// 生产模式：为 true 时拒绝使用内置的开发期默认密钥
	RequireStrongSecrets bool
	// 登录/注册按 IP 的限流窗口内允许的次数；<=0 表示关闭限流
	AuthRateLimit int64
}

func fromEnv(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

// Load 从环境变量加载配置（docker-compose / 本地均可）
func Load() *Config {
	return &Config{
		MySQLDSN:         fromEnv("IM_MYSQL_DSN", "root:root123@tcp(127.0.0.1:3306)/im?parseTime=true"),
		RedisAddr:        fromEnv("IM_REDIS_ADDR", "127.0.0.1:6379"),
		RedisPass:        fromEnv("IM_REDIS_PASS", ""),
		JWTSecret:        fromEnv("IM_JWT_SECRET", DefaultJWTSecret),
		GatewayAddr:      fromEnv("IM_GATEWAY_ADDR", ":10001"),
		LogicAddr:        fromEnv("IM_LOGIC_ADDR", ":10002"),
		StorageEndpoint:  fromEnv("IM_STORAGE_ENDPOINT", "127.0.0.1:9000"),
		StorageAccessKey: fromEnv("IM_STORAGE_ACCESS_KEY", "minioadmin"),
		StorageSecretKey: fromEnv("IM_STORAGE_SECRET_KEY", "minioadmin"),
		StorageBucket:    fromEnv("IM_STORAGE_BUCKET", "im-attachments"),
		StorageSecure:    fromEnv("IM_STORAGE_SECURE", "false") == "true",
		StorageRegion:    fromEnv("IM_STORAGE_REGION", ""),
		StoragePathStyle: fromEnv("IM_STORAGE_PATH_STYLE", "true") == "true",
		AdminToken:       fromEnv("IM_ADMIN_TOKEN", DefaultAdminToken),
		AdminAddr:        fromEnv("IM_ADMIN_ADDR", ":10003"),
		LiveKitHost:      fromEnv("IM_LIVEKIT_HOST", "ws://127.0.0.1:7880"),
		LiveKitAPIKey:    fromEnv("IM_LIVEKIT_API_KEY", "devkey"),
		LiveKitAPISecret: fromEnv("IM_LIVEKIT_API_SECRET", DefaultLiveKitSecret),

		PublicBaseURL:  strings.TrimRight(os.Getenv("IM_PUBLIC_BASE_URL"), "/"),
		AllowedOrigins: splitList(os.Getenv("IM_ALLOWED_ORIGINS")),
		TrustProxy:     os.Getenv("IM_TRUST_PROXY") == "true",
		RequireStrongSecrets: os.Getenv("IM_REQUIRE_STRONG_SECRETS") == "true" ||
			os.Getenv("IM_ENV") == "production",
		AuthRateLimit: int64(fromEnvInt("IM_AUTH_RATE_LIMIT", 60)),
	}
}

// fromEnvInt 读取非负整数环境变量，非法值回落默认
func fromEnvInt(key string, def int) int {
	v := os.Getenv(key)
	if v == "" {
		return def
	}
	n, err := strconv.Atoi(v)
	if err != nil || n < 0 {
		return def
	}
	return n
}

// 内置开发期默认值，生产部署必须覆盖
const (
	DefaultJWTSecret     = "dev-secret-change-me"
	DefaultAdminToken    = "dev-admin-token"
	DefaultLiveKitSecret = "devsecret-devsecret-devsecret-devsecre"
)

func splitList(s string) []string {
	var out []string
	for _, p := range strings.Split(s, ",") {
		if p = strings.TrimSpace(p); p != "" {
			out = append(out, p)
		}
	}
	return out
}

// devSecrets 开发期默认凭据清单
var devSecrets = []struct {
	Env   string
	Value string
}{
	{"IM_JWT_SECRET", DefaultJWTSecret},
	{"IM_ADMIN_TOKEN", DefaultAdminToken},
	{"IM_LIVEKIT_API_SECRET", DefaultLiveKitSecret},
}

// CheckSecrets 校验是否仍在使用开发期默认密钥。
// RequireStrongSecrets 时返回错误终止启动，否则打醒目告警。
func (c *Config) CheckSecrets() error {
	var weak []string
	for _, s := range devSecrets {
		if c.secretOf(s.Env) == s.Value {
			weak = append(weak, s.Env)
		}
	}
	if len(weak) == 0 {
		return nil
	}
	msg := fmt.Sprintf("正在使用开发期默认密钥: %s；生产部署请用 IM_JWT_SECRET / IM_ADMIN_TOKEN / IM_LIVEKIT_API_SECRET 覆盖", strings.Join(weak, ", "))
	if c.RequireStrongSecrets {
		return errors.New(msg)
	}
	log.Printf("[config] 警告: %s", msg)
	return nil
}

func (c *Config) secretOf(env string) string {
	switch env {
	case "IM_JWT_SECRET":
		return c.JWTSecret
	case "IM_ADMIN_TOKEN":
		return c.AdminToken
	case "IM_LIVEKIT_API_SECRET":
		return c.LiveKitAPISecret
	}
	return ""
}

// OriginAllowed 判断来源是否被允许。
// 显式配置 IM_ALLOWED_ORIGINS 时严格匹配；未配置时保持原有宽松行为
// （避免破坏本地开发与私有化部署的默认拓扑），由启动告警提示收紧。
func (c *Config) OriginAllowed(origin string) bool {
	if len(c.AllowedOrigins) == 0 {
		return true
	}
	if origin == "" { // 原生客户端（desktop/android）不带 Origin
		return true
	}
	for _, o := range c.AllowedOrigins {
		if strings.EqualFold(strings.TrimRight(o, "/"), strings.TrimRight(origin, "/")) {
			return true
		}
	}
	return false
}
