package store

import (
	"database/sql"

	_ "github.com/go-sql-driver/mysql"
	"github.com/redis/go-redis/v9"
)

// Store 封装 MySQL + Redis 访问
type Store struct {
	DB    *sql.DB
	RDB   *redis.Client
}

func NewMySQL(dsn string) (*sql.DB, error) {
	db, err := sql.Open("mysql", dsn)
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(50)
	db.SetMaxIdleConns(10)
	return db, db.Ping()
}

func NewRedis(addr, pass string) *redis.Client {
	return redis.NewClient(&redis.Options{Addr: addr, Password: pass})
}
