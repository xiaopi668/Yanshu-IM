package auth

import (
	"errors"
	"time"

	"github.com/golang-jwt/jwt/v5"
	"golang.org/x/crypto/bcrypt"
)

type Claims struct {
	UID      string `json:"uid"`
	Platform string `json:"platform,omitempty"`
	// Ver 令牌版本。账号被改密/重置/封禁时服务端递增 user_state.token_version，
	// 校验方（HTTP 中间件、WS 鉴权）发现 claims.Ver 落后即拒绝，等于即时撤销。
	// 老令牌没有这个声明，解析出来是 0，与未改过密码的账号（版本 0）兼容。
	Ver int64 `json:"ver,omitempty"`
	jwt.RegisteredClaims
}

func HashPassword(password string) (string, error) {
	b, err := bcrypt.GenerateFromPassword([]byte(password), bcrypt.DefaultCost)
	return string(b), err
}

func CheckPassword(hash, password string) bool {
	return bcrypt.CompareHashAndPassword([]byte(hash), []byte(password)) == nil
}

func MakeToken(secret, uid, platform string, ver int64) (string, error) {
	c := Claims{
		UID:      uid,
		Platform: platform,
		Ver:      ver,
		RegisteredClaims: jwt.RegisteredClaims{
			IssuedAt:  jwt.NewNumericDate(time.Now()),
			ExpiresAt: jwt.NewNumericDate(time.Now().Add(7 * 24 * time.Hour)),
		},
	}
	return jwt.NewWithClaims(jwt.SigningMethodHS256, c).SignedString([]byte(secret))
}

func ParseToken(secret, tokenStr string) (*Claims, error) {
	tok, err := jwt.ParseWithClaims(tokenStr, &Claims{}, func(t *jwt.Token) (any, error) {
		return []byte(secret), nil
	}, jwt.WithValidMethods([]string{"HS256"}))
	if err != nil {
		return nil, err
	}
	c, ok := tok.Claims.(*Claims)
	if !ok || !tok.Valid {
		return nil, errors.New("invalid token")
	}
	return c, nil
}
