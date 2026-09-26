package config

import (
	"os"
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
	LiveKitHost        string // wss://... 客户端连接地址
	LiveKitAPIKey      string
	LiveKitAPISecret   string
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
		MySQLDSN:       fromEnv("IM_MYSQL_DSN", "root:root123@tcp(127.0.0.1:3306)/im?parseTime=true"),
		RedisAddr:      fromEnv("IM_REDIS_ADDR", "127.0.0.1:6379"),
		RedisPass:      fromEnv("IM_REDIS_PASS", ""),
		JWTSecret:      fromEnv("IM_JWT_SECRET", "dev-secret-change-me"),
		GatewayAddr:    fromEnv("IM_GATEWAY_ADDR", ":10001"),
		LogicAddr:      fromEnv("IM_LOGIC_ADDR", ":10002"),
		StorageEndpoint:  fromEnv("IM_STORAGE_ENDPOINT", "127.0.0.1:9000"),
		StorageAccessKey: fromEnv("IM_STORAGE_ACCESS_KEY", "minioadmin"),
		StorageSecretKey: fromEnv("IM_STORAGE_SECRET_KEY", "minioadmin"),
		StorageBucket:    fromEnv("IM_STORAGE_BUCKET", "im-attachments"),
		StorageSecure:    fromEnv("IM_STORAGE_SECURE", "false") == "true",
		StorageRegion:    fromEnv("IM_STORAGE_REGION", ""),
		StoragePathStyle: fromEnv("IM_STORAGE_PATH_STYLE", "true") == "true",
		AdminToken:       fromEnv("IM_ADMIN_TOKEN", "dev-admin-token"),
		AdminAddr:        fromEnv("IM_ADMIN_ADDR", ":10003"),
		LiveKitHost:        fromEnv("IM_LIVEKIT_HOST", "ws://127.0.0.1:7880"),
		LiveKitAPIKey:      fromEnv("IM_LIVEKIT_API_KEY", "devkey"),
		LiveKitAPISecret:   fromEnv("IM_LIVEKIT_API_SECRET", "devsecret-devsecret-devsecret-devsecre"),
	}
}
