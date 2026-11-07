package main

// 两步验证（TOTP）：自助绑定/关闭 + 登录时的第二因子校验。
//
// 设计要点：
// - 密钥只存 base32、验证码不落库；
// - 恢复码只存 SHA-256 且一次性消费（手机丢了靠它登录）；
// - 登录时「密码对不对」和「第二因子对不对」分成两次判定，但对外都报 401 ——
//   唯一区别是响应体里的 totp_required，客户端据此把验证码输入框显示出来。
// - 所有校验都带频率限制：6 位数字只有一百万种，没有限速就是可爆破的。

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"net/http"
	"strings"
	"time"

	"github.com/skip2/go-qrcode"

	"im/internal/auth"
)

const (
	totpIssuer        = "雁书"
	totpRecoveryCount = 10
	// totpSkew：允许前后各一个 30 秒步长的时钟偏差。手机与服务器的时钟经常差几十秒，
	// 不放宽的话用户会遇到「刚生成的码就提示不对」。再宽就等于给爆破留窗口了。
	totpSkew = 1
)

// totpState 读取某用户的两步验证状态。没有记录时返回 enabled=false。
func (a *apiv1) totpState(ctx context.Context, uid string) (secret string, enabled bool, recoveryLeft int, err error) {
	err = a.db.QueryRowContext(ctx,
		`SELECT secret, enabled FROM user_totp WHERE uid=?`, uid).Scan(&secret, &enabled)
	if errors.Is(err, sql.ErrNoRows) {
		return "", false, 0, nil
	}
	if err != nil {
		return "", false, 0, err
	}
	_ = a.db.QueryRowContext(ctx,
		`SELECT COUNT(*) FROM user_totp_recovery WHERE uid=? AND used_at IS NULL`, uid).Scan(&recoveryLeft)
	return secret, enabled, recoveryLeft, nil
}

// totpStatus GET /v1/me/totp —— 只回状态，不回密钥
func (a *apiv1) totpStatus(w http.ResponseWriter, r *http.Request, uid string) {
	_, enabled, left, err := a.totpState(r.Context(), uid)
	if err != nil {
		fail(w, 500, err)
		return
	}
	writeJSON(w, 200, map[string]any{"enabled": enabled, "recovery_left": left})
}

// totpSetup POST /v1/me/totp/setup —— 生成密钥，返回 otpauth:// 供扫码
//
// 此时还没有 enabled：必须再调 /enable 并提交一次验证码，证明验证器真的配好了。
// 否则用户扫完码就关页面，会把自己锁在门外。
func (a *apiv1) totpSetup(w http.ResponseWriter, r *http.Request, uid string) {
	if err := a.rateLimit(r, "totp-setup", a.cfg.AuthRateLimit, 5*time.Minute); err != nil {
		fail(w, 429, err)
		return
	}
	_, enabled, _, err := a.totpState(r.Context(), uid)
	if err != nil {
		fail(w, 500, err)
		return
	}
	if enabled {
		fail(w, 409, errors.New("两步验证已开启；如需重新绑定请先关闭"))
		return
	}
	secret, err := auth.GenerateSecret()
	if err != nil {
		fail(w, 500, err)
		return
	}
	// 覆盖旧草稿（重新点「开启」时换了密钥，旧的必须失效）
	if _, err := a.db.ExecContext(r.Context(),
		`INSERT INTO user_totp(uid, secret, enabled, created_at) VALUES(?,?,0,?) `+
			`ON DUPLICATE KEY UPDATE secret=VALUES(secret), enabled=0, created_at=VALUES(created_at)`,
		uid, secret, time.Now().UnixMilli()); err != nil {
		fail(w, 500, err)
		return
	}
	// 账号名用用户名（验证器里显示得出来）
	var username string
	_ = a.db.QueryRowContext(r.Context(), `SELECT username FROM user WHERE uid=?`, uid).Scan(&username)
	if username == "" {
		username = uid
	}
	otpauth := auth.OtpauthURL(totpIssuer, username, secret)
	// 二维码矩阵由服务端生成（见 qrRows 的注释）：各端只负责按矩阵画点，
	// 不用为每个平台各绑一个二维码库，也不会有人图省事去调在线二维码 API。
	rows, err := qrRows(otpauth)
	if err != nil {
		fail(w, 500, err)
		return
	}
	writeJSON(w, 200, map[string]any{
		"secret":      secret,
		"otpauth_url": otpauth,
		"qr_rows":     rows,
		"qr_size":     len(rows),
	})
}

// totpEnable POST /v1/me/totp/enable —— 校验一次验证码，正式开启并下发恢复码
func (a *apiv1) totpEnable(w http.ResponseWriter, r *http.Request, uid string) {
	if err := a.rateLimit(r, "totp-enable", a.cfg.AuthRateLimit, 5*time.Minute); err != nil {
		fail(w, 429, err)
		return
	}
	var req struct {
		Code string `json:"code"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		fail(w, 400, err)
		return
	}
	secret, enabled, _, err := a.totpState(r.Context(), uid)
	if err != nil {
		fail(w, 500, err)
		return
	}
	if secret == "" {
		fail(w, 400, errors.New("请先调用 /v1/me/totp/setup 获取密钥"))
		return
	}
	if enabled {
		fail(w, 409, errors.New("两步验证已开启"))
		return
	}
	if !auth.Verify(secret, req.Code, time.Now(), totpSkew) {
		fail(w, 400, errors.New("验证码不正确，请确认手机时间准确后重试"))
		return
	}
	codes, err := auth.RecoveryCodes(totpRecoveryCount)
	if err != nil {
		fail(w, 500, err)
		return
	}
	tx, err := a.db.BeginTx(r.Context(), nil)
	if err != nil {
		fail(w, 500, err)
		return
	}
	defer func() { _ = tx.Rollback() }()
	if _, err := tx.ExecContext(r.Context(), `UPDATE user_totp SET enabled=1 WHERE uid=?`, uid); err != nil {
		fail(w, 500, err)
		return
	}
	if _, err := tx.ExecContext(r.Context(), `DELETE FROM user_totp_recovery WHERE uid=?`, uid); err != nil {
		fail(w, 500, err)
		return
	}
	for _, c := range codes {
		if _, err := tx.ExecContext(r.Context(),
			`INSERT INTO user_totp_recovery(uid, code_hash) VALUES(?,?)`, uid, auth.HashRecovery(c)); err != nil {
			fail(w, 500, err)
			return
		}
	}
	if err := tx.Commit(); err != nil {
		fail(w, 500, err)
		return
	}
	// 恢复码只在这里返回一次，之后库里只有哈希
	writeJSON(w, 200, map[string]any{"recovery_codes": codes})
}

// totpDisable POST /v1/me/totp/disable —— 必须提供当前验证码（或恢复码）
func (a *apiv1) totpDisable(w http.ResponseWriter, r *http.Request, uid string) {
	if err := a.rateLimit(r, "totp-disable", a.cfg.AuthRateLimit, 5*time.Minute); err != nil {
		fail(w, 429, err)
		return
	}
	var req struct {
		Code string `json:"code"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		fail(w, 400, err)
		return
	}
	secret, enabled, _, err := a.totpState(r.Context(), uid)
	if err != nil {
		fail(w, 500, err)
		return
	}
	if !enabled {
		fail(w, 400, errors.New("两步验证未开启"))
		return
	}
	ok := auth.Verify(secret, strings.TrimSpace(req.Code), time.Now(), totpSkew)
	if !ok {
		// 手机丢了的人要能靠恢复码关掉重绑
		ok, err = a.consumeRecovery(r.Context(), uid, req.Code)
		if err != nil {
			fail(w, 500, err)
			return
		}
	}
	if !ok {
		fail(w, 401, errors.New("验证码或恢复码不正确"))
		return
	}
	if _, err := a.db.ExecContext(r.Context(), `DELETE FROM user_totp WHERE uid=?`, uid); err != nil {
		fail(w, 500, err)
		return
	}
	if _, err := a.db.ExecContext(r.Context(), `DELETE FROM user_totp_recovery WHERE uid=?`, uid); err != nil {
		fail(w, 500, err)
		return
	}
	writeJSON(w, 200, map[string]any{"enabled": false})
}

// consumeRecovery 消费一个恢复码（一次性）。命中后立即标记，防重复使用。
func (a *apiv1) consumeRecovery(ctx context.Context, uid, code string) (bool, error) {
	code = strings.TrimSpace(code)
	if code == "" {
		return false, nil
	}
	h := auth.HashRecovery(code)
	res, err := a.db.ExecContext(ctx,
		`UPDATE user_totp_recovery SET used_at=? WHERE uid=? AND code_hash=? AND used_at IS NULL`,
		time.Now().UnixMilli(), uid, h)
	if err != nil {
		return false, err
	}
	n, err := res.RowsAffected()
	if err != nil {
		return false, err
	}
	return n > 0, nil
}

// verifyLoginTotp 登录第二因子校验：返回「是否通过」与「是否需要验证码」。
//
// 之所以返回两个值：调用方要区分「这个账号根本没开 2FA」和「开了但码不对」，
// 前者直接放行，后者要提示用户重新输入而不是说密码错误。
func (a *apiv1) verifyLoginTotp(ctx context.Context, uid, code string) (ok bool, required bool, err error) {
	secret, enabled, _, err := a.totpState(ctx, uid)
	if err != nil || !enabled {
		return true, false, err
	}
	code = strings.TrimSpace(code)
	if code == "" {
		return false, true, nil
	}
	if auth.Verify(secret, code, time.Now(), totpSkew) {
		return true, true, nil
	}
	used, err := a.consumeRecovery(ctx, uid, code)
	if err != nil {
		return false, true, err
	}
	return used, true, nil
}

// qrRows 把 otpauth 地址编成二维码矩阵（每行一串 '0'/'1'，已含 4 模块静区）。
//
// 为什么在服务端算：
// - 客户端三端（Android / 桌面 / Web）各绑一个二维码库，成本与风险都高于这一处；
// - 更要紧的是**绝不能用在线二维码 API** —— 那等于把用户的 TOTP 密钥发给第三方。
// 这里用的是纯本地编码（skip2/go-qrcode），内容不出服务器。
//
// 返回矩阵而不是图片：客户端画点即可（Compose Canvas），不需要图片解码能力，
// 也不受各端图片加载差异影响。
func qrRows(content string) ([]string, error) {
	// 中等纠错：屏幕上扫得动，也不会把码撑得太大
	q, err := qrcode.New(content, qrcode.Medium)
	if err != nil {
		return nil, err
	}
	q.DisableBorder = false // 静区不能省：多数扫码器没有静区就定位不到
	bitmap := q.Bitmap()
	rows := make([]string, 0, len(bitmap))
	for _, line := range bitmap {
		var sb strings.Builder
		sb.Grow(len(line))
		for _, dark := range line {
			if dark {
				sb.WriteByte('1')
			} else {
				sb.WriteByte('0')
			}
		}
		rows = append(rows, sb.String())
	}
	return rows, nil
}
