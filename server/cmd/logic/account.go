package main

// 账号自助：改密码 / 改邮箱 / 退出其它设备。
//
// 这三件事都与「令牌版本」有关：user_state.token_version 是让旧令牌立即失效的唯一手段，
// 中间件与网关都会拿 claims.Ver 与它比对。注意：递增之后必须给**当前**设备签发新令牌，
// 否则改完密码把自己也踢下线了。

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"regexp"
	"strings"
	"time"

	"im/internal/auth"
	"im/internal/siteconf"
	"im/internal/userstate"
)

var emailRe = regexp.MustCompile(`^[^@\s]+@[^@\s]+\.[^@\s]+$`)

// changePassword 修改密码（需要旧密码）
func (a *apiv1) changePassword(w http.ResponseWriter, r *http.Request, uid string) {
	if err := a.rateLimit(r, "change-password", a.cfg.AuthRateLimit, 5*time.Minute); err != nil {
		fail(w, 429, err)
		return
	}
	var req struct {
		OldPassword string `json:"old_password"`
		NewPassword string `json:"new_password"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		fail(w, 400, err)
		return
	}
	if len(req.NewPassword) < 6 {
		fail(w, 400, errors.New("新密码至少 6 位"))
		return
	}
	if req.NewPassword == req.OldPassword {
		fail(w, 400, errors.New("新密码不能与当前密码相同"))
		return
	}
	var hash string
	if err := a.db.QueryRow(`SELECT COALESCE(password_hash,'') FROM user WHERE uid=?`, uid).Scan(&hash); err != nil {
		fail(w, 404, err)
		return
	}
	// 纯 OIDC 账号可能没有本地口令（hash 为空），这种情况允许直接设置
	if hash != "" && !auth.CheckPassword(hash, req.OldPassword) {
		fail(w, 401, errors.New("当前密码不正确"))
		return
	}
	nh, err := auth.HashPassword(req.NewPassword)
	if err != nil {
		fail(w, 500, err)
		return
	}
	if _, err := a.db.Exec(`UPDATE user SET password_hash=? WHERE uid=?`, nh, uid); err != nil {
		fail(w, 500, err)
		return
	}
	tok, err := a.reissueToken(r.Context(), uid)
	if err != nil {
		fail(w, 500, err)
		return
	}
	writeJSON(w, 200, map[string]string{
		"token": tok,
		"note":  "其它设备已下线",
	})
}

// logoutOtherDevices 让其它设备下线；当前设备拿到新令牌继续用
func (a *apiv1) logoutOtherDevices(w http.ResponseWriter, r *http.Request, uid string) {
	if err := a.rateLimit(r, "logout-others", a.cfg.AuthRateLimit, 5*time.Minute); err != nil {
		fail(w, 429, err)
		return
	}
	tok, err := a.reissueToken(r.Context(), uid)
	if err != nil {
		fail(w, 500, err)
		return
	}
	writeJSON(w, 200, map[string]string{"token": tok})
}

// setEmail 绑定 / 修改邮箱。
// 站点开启邮箱验证码时要求带 code（与注册同一套校验），否则直接改。
func (a *apiv1) setEmail(w http.ResponseWriter, r *http.Request, uid string) {
	var req struct {
		Email string `json:"email"`
		Code  string `json:"code"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		fail(w, 400, err)
		return
	}
	email := strings.ToLower(strings.TrimSpace(req.Email))
	if email == "" {
		// 允许解绑（清空）
		if _, err := a.db.Exec(`UPDATE user SET email=NULL WHERE uid=?`, uid); err != nil {
			fail(w, 500, err)
			return
		}
		writeJSON(w, 200, map[string]string{"email": ""})
		return
	}
	if !emailRe.MatchString(email) || len(email) > 128 {
		fail(w, 400, errors.New("邮箱格式不正确"))
		return
	}
	conf := a.siteConf()
	if conf.EmailCodeEnabled {
		if err := siteconf.CheckEmailCode(a.rdb, email, strings.TrimSpace(req.Code)); err != nil {
			fail(w, 400, err)
			return
		}
	}
	// 邮箱可用于登录，重复会让登录变成"帐号歧义"（lookupLoginUser 会返回 409）
	var other string
	err := a.db.QueryRow(`SELECT uid FROM user WHERE email=? AND uid<>? LIMIT 1`, email, uid).Scan(&other)
	if err == nil && other != "" {
		fail(w, 409, errors.New("该邮箱已被其它账号使用"))
		return
	}
	if _, err := a.db.Exec(`UPDATE user SET email=? WHERE uid=?`, email, uid); err != nil {
		fail(w, 500, err)
		return
	}
	// 注：CheckEmailCode 内部已消费验证码，不需要额外删除
	writeJSON(w, 200, map[string]string{"email": email})
}

// reissueToken 递增令牌版本（旧令牌立即失效）并给当前设备签发新令牌
func (a *apiv1) reissueToken(ctx context.Context, uid string) (string, error) {
	if err := userstate.BumpTokenVersion(ctx, a.db.DB, uid); err != nil {
		return "", err
	}
	userstate.Invalidate(ctx, a.rdb, uid)
	st, err := userstate.Get(ctx, a.db.DB, a.rdb, uid)
	if err != nil {
		return "", err
	}
	return auth.MakeToken(a.cfg.JWTSecret, uid, "reauth", st.TokenVersion)
}
