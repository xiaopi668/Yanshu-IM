package main

// 通行密钥（WebAuthn / Passkey）。

// 为什么用 go-webauthn 而不是自己写：WebAuthn 要处理 CBOR 解析、COSE 公钥、
// ES256/RS256/EdDSA 签名验证、origin 与 RP ID 校验、签名计数器回滚检测 ——
// 任何一处写错都是「看起来能用、实际可被绕过」。TOTP 手写 80 行还行，这个不行。

// 挑战（challenge）放 Redis，5 分钟过期：它是防重放的唯一凭据，不能只留在客户端。

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"time"

	"github.com/go-webauthn/webauthn/protocol"
	"github.com/go-webauthn/webauthn/webauthn"
	"github.com/google/uuid"

	"im/internal/auth"
	"im/internal/userstate"
)

const passkeyChallengeTTL = 5 * time.Minute

// passkeyUser 把本站用户适配成 webauthn.User
type passkeyUser struct {
	uid         string
	name        string
	credentials []webauthn.Credential
}

func (u *passkeyUser) WebAuthnID() []byte                         { return []byte(u.uid) }
func (u *passkeyUser) WebAuthnName() string                       { return u.name }
func (u *passkeyUser) WebAuthnDisplayName() string                { return u.name }
func (u *passkeyUser) WebAuthnCredentials() []webauthn.Credential { return u.credentials }

// webauthnFor 按配置构造 WebAuthn 实例。未配置 RP 时返回 nil（功能关闭）。
//
// RP ID 必须是站点域名（不含 scheme/端口），RPOrigins 是浏览器地址栏里的 origin。
// 配错的表现是浏览器直接报 SecurityError —— 所以这里把两者都做成可覆盖的配置。
func (a *apiv1) webauthnFor() *webauthn.WebAuthn {
	if a.cfg.WebauthnRPID == "" {
		return nil
	}
	w, err := webauthn.New(&webauthn.Config{
		RPID:          a.cfg.WebauthnRPID,
		RPDisplayName: a.cfg.WebauthnDisplayName,
		RPOrigins:     a.cfg.WebauthnOrigins,
	})
	if err != nil {
		return nil
	}
	return w
}

// loadPasskeyUser 读出用户的全部凭据，适配成 webauthn.User
func (a *apiv1) loadPasskeyUser(ctx context.Context, uid string) (*passkeyUser, error) {
	var name string
	if err := a.db.QueryRowContext(ctx, `SELECT username FROM user WHERE uid=?`, uid).Scan(&name); err != nil {
		return nil, err
	}
	rows, err := a.db.QueryContext(ctx, `SELECT credential_json FROM user_passkey WHERE uid=?`, uid)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var creds []webauthn.Credential
	for rows.Next() {
		var raw string
		if err := rows.Scan(&raw); err != nil {
			return nil, err
		}
		var c webauthn.Credential
		if err := json.Unmarshal([]byte(raw), &c); err == nil {
			creds = append(creds, c)
		}
	}
	return &passkeyUser{uid: uid, name: name, credentials: creds}, rows.Err()
}

func (a *apiv1) saveChallenge(ctx context.Context, key string, s *webauthn.SessionData) error {
	raw, err := json.Marshal(s)
	if err != nil {
		return err
	}
	return a.rdb.Set(ctx, "im:pk:"+key, raw, passkeyChallengeTTL).Err()
}

func (a *apiv1) takeChallenge(ctx context.Context, key string) (*webauthn.SessionData, error) {
	raw, err := a.rdb.Get(ctx, "im:pk:"+key).Bytes()
	if err != nil {
		return nil, errors.New("通行密钥会话已过期，请重试")
	}
	// 一次性：取出来就删，避免同一挑战被重放
	a.rdb.Del(ctx, "im:pk:"+key)
	var s webauthn.SessionData
	if err := json.Unmarshal(raw, &s); err != nil {
		return nil, err
	}
	return &s, nil
}

// passkeyBegin POST /v1/me/passkey/begin —— 已登录用户绑定新通行密钥
func (a *apiv1) passkeyBegin(w http.ResponseWriter, r *http.Request, uid string) {
	wa := a.webauthnFor()
	if wa == nil {
		fail(w, 503, errors.New("本站未配置通行密钥（需要 IM_PUBLIC_BASE_URL 或 IM_WEBAUTHN_RP_ID）"))
		return
	}
	u, err := a.loadPasskeyUser(r.Context(), uid)
	if err != nil {
		fail(w, 500, err)
		return
	}
	creation, session, err := wa.BeginRegistration(u,
		webauthn.WithExclusions(webauthn.Credentials(u.credentials).CredentialDescriptors()),
		// **Must be required，不能是 preferred**：preferred 允许认证器把凭据存成
		// 「服务端凭据」（非 discoverable），那样无用户名登录时浏览器根本找不到它，
		// 表现就是「明明绑定成功，登录却说未找到此应用程序的通行密钥」。
		// 通行密钥（passkey）的定义就是 discoverable + 用户验证，这里不能退让。
		webauthn.WithResidentKeyRequirement(protocol.ResidentKeyRequirementRequired),
		webauthn.WithAuthenticatorSelection(protocol.AuthenticatorSelection{
			ResidentKey:      protocol.ResidentKeyRequirementRequired,
			UserVerification: protocol.VerificationRequired,
		}),
	)
	if err != nil {
		fail(w, 500, err)
		return
	}
	if err := a.saveChallenge(r.Context(), "reg:"+uid, session); err != nil {
		fail(w, 500, err)
		return
	}
	writeJSON(w, 200, creation)
}

// passkeyFinish POST /v1/me/passkey/finish —— 校验注册结果并入库
func (a *apiv1) passkeyFinish(w http.ResponseWriter, r *http.Request, uid string) {
	wa := a.webauthnFor()
	if wa == nil {
		fail(w, 503, errors.New("本站未配置通行密钥"))
		return
	}
	sess, err := a.takeChallenge(r.Context(), "reg:"+uid)
	if err != nil {
		fail(w, 400, err)
		return
	}
	u, err := a.loadPasskeyUser(r.Context(), uid)
	if err != nil {
		fail(w, 500, err)
		return
	}
	cred, err := wa.FinishRegistration(u, *sess, r)
	if err != nil {
		fail(w, 400, fmt.Errorf("通行密钥注册失败: %w", err))
		return
	}
	raw, err := json.Marshal(cred)
	if err != nil {
		fail(w, 500, err)
		return
	}
	label := strings.TrimSpace(r.URL.Query().Get("label"))
	if len(label) > 64 {
		label = label[:64]
	}
	if _, err := a.db.ExecContext(r.Context(),
		`INSERT INTO user_passkey(credential_id, uid, credential_json, label, created_at) VALUES(?,?,?,?,?)`,
		cred.ID, uid, string(raw), label, time.Now().UnixMilli()); err != nil {
		fail(w, 409, errors.New("该通行密钥已绑定过"))
		return
	}
	writeJSON(w, 200, map[string]any{"ok": true, "label": label})
}

// passkeyList GET /v1/me/passkey
func (a *apiv1) passkeyList(w http.ResponseWriter, r *http.Request, uid string) {
	rows, err := a.db.QueryContext(r.Context(),
		`SELECT credential_id, label, created_at, COALESCE(last_used_at,0) FROM user_passkey WHERE uid=? ORDER BY created_at DESC`, uid)
	if err != nil {
		fail(w, 500, err)
		return
	}
	defer rows.Close()
	type item struct {
		ID         string `json:"id"`
		Label      string `json:"label"`
		CreatedAt  int64  `json:"created_at"`
		LastUsedAt int64  `json:"last_used_at"`
	}
	out := []item{}
	for rows.Next() {
		var id []byte
		var it item
		if err := rows.Scan(&id, &it.Label, &it.CreatedAt, &it.LastUsedAt); err != nil {
			fail(w, 500, err)
			return
		}
		it.ID = auth.Base64URL(id)
		out = append(out, it)
	}
	writeJSON(w, 200, out)
}

// passkeyDelete DELETE /v1/me/passkey/{id}
func (a *apiv1) passkeyDelete(w http.ResponseWriter, r *http.Request, uid string) {
	id, err := auth.Base64URLDecode(r.PathValue("id"))
	if err != nil {
		fail(w, 400, errors.New("id 非法"))
		return
	}
	res, err := a.db.ExecContext(r.Context(),
		`DELETE FROM user_passkey WHERE credential_id=? AND uid=?`, id, uid)
	if err != nil {
		fail(w, 500, err)
		return
	}
	if n, _ := res.RowsAffected(); n == 0 {
		fail(w, 404, errors.New("未找到该通行密钥"))
		return
	}
	writeJSON(w, 200, map[string]any{"ok": true})
}

// passkeyLoginBegin POST /v1/passkey/login/begin —— 无用户名（discoverable）登录
func (a *apiv1) passkeyLoginBegin(w http.ResponseWriter, r *http.Request) {
	wa := a.webauthnFor()
	if wa == nil {
		fail(w, 503, errors.New("本站未配置通行密钥"))
		return
	}
	// 两种流程：
	// - 不填用户名 → discoverable（认证器自己挑凭据），需要凭据是 resident；
	// - 填了用户名 → allowCredentials，能把**早期用 preferred 建的非 resident 凭据**也救回来。
	var req struct {
		Username string `json:"username"`
	}
	_ = json.NewDecoder(r.Body).Decode(&req)
	var (
		assertion *protocol.CredentialAssertion
		session   *webauthn.SessionData
		err       error
	)
	if name := strings.TrimSpace(req.Username); name != "" {
		uid, _, lerr := a.lookupLoginUser(name)
		if lerr != nil {
			fail(w, 401, errors.New("未找到该账号的通行密钥"))
			return
		}
		u, lerr := a.loadPasskeyUser(r.Context(), uid)
		if lerr != nil || len(u.credentials) == 0 {
			fail(w, 401, errors.New("该账号还没有绑定通行密钥"))
			return
		}
		assertion, session, err = wa.BeginLogin(u,
			webauthn.WithUserVerification(protocol.VerificationRequired),
		)
	} else {
		assertion, session, err = wa.BeginDiscoverableLogin(
			webauthn.WithUserVerification(protocol.VerificationRequired),
		)
	}
	if err != nil {
		fail(w, 500, err)
		return
	}
	// 挑战用随机 id 存：此时还不知道是谁在登录
	key := "login:" + uuid.NewString()
	if err := a.saveChallenge(r.Context(), key, session); err != nil {
		fail(w, 500, err)
		return
	}
	writeJSON(w, 200, map[string]any{"options": assertion, "session_id": strings.TrimPrefix(key, "login:")})
}

// passkeyLoginFinish POST /v1/passkey/login/finish —— 校验断言并签发令牌
func (a *apiv1) passkeyLoginFinish(w http.ResponseWriter, r *http.Request) {
	wa := a.webauthnFor()
	if wa == nil {
		fail(w, 503, errors.New("本站未配置通行密钥"))
		return
	}
	sid := r.URL.Query().Get("session_id")
	sess, err := a.takeChallenge(r.Context(), "login:"+sid)
	if err != nil {
		fail(w, 400, err)
		return
	}
	var matchedUID string
	handler := func(rawID, userHandle []byte) (webauthn.User, error) {
		var uid string
		if err := a.db.QueryRowContext(r.Context(),
			`SELECT uid FROM user_passkey WHERE credential_id=?`, rawID).Scan(&uid); err != nil {
			return nil, errors.New("未识别的通行密钥")
		}
		u, err := a.loadPasskeyUser(r.Context(), uid)
		if err != nil {
			return nil, err
		}
		matchedUID = uid
		return u, nil
	}
	_, cred, err := wa.FinishPasskeyLogin(handler, *sess, r)
	if err != nil {
		fail(w, 401, fmt.Errorf("通行密钥验证失败: %w", err))
		return
	}
	if matchedUID == "" {
		fail(w, 401, errors.New("未识别的通行密钥"))
		return
	}
	// 更新签名计数器与最近使用时间（计数器回滚由库在验证阶段拒绝）
	if raw, err := json.Marshal(cred); err == nil {
		_, _ = a.db.ExecContext(r.Context(),
			`UPDATE user_passkey SET credential_json=?, last_used_at=? WHERE credential_id=?`,
			string(raw), time.Now().UnixMilli(), cred.ID)
	}
	st, err := userstate.Get(r.Context(), a.db.DB, a.rdb, matchedUID)
	if err != nil {
		fail(w, 503, errors.New("account state unavailable"))
		return
	}
	if st.Disabled {
		fail(w, 403, errors.New("account disabled"))
		return
	}
	// 通行密钥本身就是「持有设备 + 生物识别/PIN」的双因子，不再要求 TOTP
	tok, err := auth.MakeToken(a.cfg.JWTSecret, matchedUID, "passkey", st.TokenVersion)
	if err != nil {
		fail(w, 500, err)
		return
	}
	writeJSON(w, 200, map[string]string{"uid": matchedUID, "token": tok})
}
