package store

import (
	"database/sql"

	_ "github.com/go-sql-driver/mysql"
	"github.com/redis/go-redis/v9"
)

// NewMySQL 建立 MySQL 连接池
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
