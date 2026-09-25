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
	// MinIO
	MinioEndpoint  string
	MinioAccessKey string
	MinioSecretKey string
	MinioBucket    string
	MinioSecure    bool
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
		MinioEndpoint:  fromEnv("IM_MINIO_ENDPOINT", "127.0.0.1:9000"),
		MinioAccessKey: fromEnv("IM_MINIO_ACCESS_KEY", "minioadmin"),
		MinioSecretKey: fromEnv("IM_MINIO_SECRET_KEY", "minioadmin"),
		MinioBucket:    fromEnv("IM_MINIO_BUCKET", "im-attachments"),
		MinioSecure:    fromEnv("IM_MINIO_SECURE", "false") == "true",
		LiveKitHost:    fromEnv("IM_LIVEKIT_HOST", "ws://127.0.0.1:7880"),
		LiveKitAPIKey:  fromEnv("IM_LIVEKIT_API_KEY", "devkey"),
		LiveKitAPISecret: fromEnv("IM_LIVEKIT_API_SECRET", "devsecret-devsecret-devsecret-devsecre"),
	}
}
