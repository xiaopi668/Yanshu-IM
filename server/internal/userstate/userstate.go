// Package userstate 读取与失效账号状态（是否封禁 + 令牌版本）。
//
// 为什么需要它：早期实现只在「登录」和「WS 首帧鉴权」查 user_state，
// 于是封禁对 REST 接口形同虚设 —— 被封用户拿着旧 JWT 照样能拉历史、加好友、发朋友圈；
// 管理员重置密码也切不断攻击者已经拿到的会话（JWT 自包含，有效期 7 天）。
//
// 现在每个 HTTP 请求都校验这两项。为了不把一次 DB 查询加到每个请求上，
// 结果缓存在 Redis（短 TTL），并且**管理后台改状态时主动失效缓存**，
// 因此封禁/改密是立即生效的，而不是等 TTL 过期。
package userstate

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"strconv"
	"strings"
	"time"

	"github.com/redis/go-redis/v9"
)

const (
	cachePrefix = "im:ustate:"
	// cacheTTL 兜底过期时间。正常情况下靠 Invalidate 主动失效，
	// 这个 TTL 只是为了不让 Redis 里留下永远不更新的陈旧状态。
	cacheTTL = 5 * time.Minute
)

// State 账号状态
type State struct {
	Disabled     bool
	TokenVersion int64
}

// Get 读取账号状态：先查 Redis 缓存，未命中再落库。
// 没有 user_state 行等价于「未封禁、版本 0」（绝大多数正常账号都是这个状态）。
func Get(ctx context.Context, db *sql.DB, rdb *redis.Client, uid string) (State, error) {
	key := cachePrefix + uid
	if rdb != nil {
		if raw, err := rdb.Get(ctx, key).Result(); err == nil {
			if st, ok := parse(raw); ok {
				return st, nil
			}
		}
		// Redis 出错不视为失败：继续落库读，正确性优先于性能
	}

	var disabled int
	var ver int64
	err := db.QueryRowContext(ctx, "SELECT disabled, token_version FROM user_state WHERE uid=?", uid).
		Scan(&disabled, &ver)
	if errors.Is(err, sql.ErrNoRows) {
		cacheSet(ctx, rdb, key, State{})
		return State{}, nil
	}
	if err != nil {
		return State{}, err
	}
	st := State{Disabled: disabled == 1, TokenVersion: ver}
	cacheSet(ctx, rdb, key, st)
	return st, nil
}

// Invalidate 删除缓存。管理后台改状态后必须调用，否则最长要等 cacheTTL 才生效。
func Invalidate(ctx context.Context, rdb *redis.Client, uid string) {
	if rdb == nil {
		return
	}
	_ = rdb.Del(ctx, cachePrefix+uid).Err()
}

// BumpTokenVersion 递增令牌版本，使该账号已签发的所有 JWT 立即失效。
// 不存在状态行时直接建一行（版本 1）。
func BumpTokenVersion(ctx context.Context, db *sql.DB, uid string) error {
	_, err := db.ExecContext(ctx,
		"INSERT INTO user_state(uid, disabled, token_version) VALUES(?, 0, 1) "+
			"ON DUPLICATE KEY UPDATE token_version = token_version + 1", uid)
	return err
}

func parse(raw string) (State, bool) {
	dis, ver, ok := strings.Cut(raw, ":")
	if !ok {
		return State{}, false
	}
	d, err := strconv.Atoi(dis)
	if err != nil {
		return State{}, false
	}
	v, err := strconv.ParseInt(ver, 10, 64)
	if err != nil {
		return State{}, false
	}
	return State{Disabled: d == 1, TokenVersion: v}, true
}

func cacheSet(ctx context.Context, rdb *redis.Client, key string, st State) {
	if rdb == nil {
		return
	}
	d := 0
	if st.Disabled {
		d = 1
	}
	_ = rdb.Set(ctx, key, fmt.Sprintf("%d:%d", d, st.TokenVersion), cacheTTL).Err()
}
