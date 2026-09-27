package siteconf

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"
)

// discovery 缓存（进程内 1 小时）
var (
	discMu     sync.Mutex
	discovered = map[string]oidcDiscovery{}
)

type oidcDiscovery struct {
	AuthorizeURL string `json:"authorization_endpoint"`
	TokenURL     string `json:"token_endpoint"`
	UserInfoURL  string `json:"userinfo_endpoint"`
	Expires      time.Time
}

// Discover 按 issuer 拉取 OIDC 发现文档（带缓存）
func Discover(issuer string) (oidcDiscovery, error) {
	discMu.Lock()
	if d, ok := discovered[issuer]; ok && time.Now().Before(d.Expires) {
		discMu.Unlock()
		return d, nil
	}
	discMu.Unlock()
	if !strings.HasPrefix(issuer, "https://") && !strings.HasPrefix(issuer, "http://") {
		return oidcDiscovery{}, errors.New("issuer 必须是 http(s) URL")
	}
	client := &http.Client{Timeout: 10 * time.Second}
	resp, err := client.Get(strings.TrimSuffix(issuer, "/") + "/.well-known/openid-configuration")
	if err != nil {
		return oidcDiscovery{}, err
	}
	defer resp.Body.Close()
	var d oidcDiscovery
	body, _ := io.ReadAll(resp.Body)
	if err := json.Unmarshal(body, &d); err != nil {
		return oidcDiscovery{}, fmt.Errorf("OIDC 发现文档解析失败: %w", err)
	}
	if d.AuthorizeURL == "" || d.TokenURL == "" {
		return oidcDiscovery{}, errors.New("OIDC 发现文档缺少 endpoint")
	}
	d.Expires = time.Now().Add(time.Hour)
	discMu.Lock()
	discovered[issuer] = d
	discMu.Unlock()
	return d, nil
}

// TokenExchange 用授权码换 access token
func TokenExchange(d oidcDiscovery, p OIDCProvider, code, redirectURI string) (string, error) {
	form := url.Values{
		"grant_type":    {"authorization_code"},
		"code":          {code},
		"redirect_uri":  {redirectURI},
		"client_id":     {p.ClientID},
		"client_secret": {p.ClientSecret},
	}
	resp, err := http.PostForm(d.TokenURL, form)
	if err != nil {
		return "", err
	}
	defer resp.Body.Close()
	var tr struct {
		AccessToken string `json:"access_token"`
		Error       string `json:"error"`
	}
	body, _ := io.ReadAll(resp.Body)
	if err := json.Unmarshal(body, &tr); err != nil {
		return "", err
	}
	if tr.AccessToken == "" {
		return "", fmt.Errorf("换 token 失败: %s", tr.Error)
	}
	return tr.AccessToken, nil
}

// FetchUserInfo 拉取 userinfo（sub / preferred_username / email）
func FetchUserInfo(d oidcDiscovery, accessToken string) (sub, name, email string, err error) {
	req, _ := http.NewRequestWithContext(context.Background(), "GET", d.UserInfoURL, nil)
	req.Header.Set("Authorization", "Bearer "+accessToken)
	client := &http.Client{Timeout: 10 * time.Second}
	resp, err := client.Do(req)
	if err != nil {
		return "", "", "", err
	}
	defer resp.Body.Close()
	var ui struct {
		Sub               string `json:"sub"`
		PreferredUsername string `json:"preferred_username"`
		Name              string `json:"name"`
		Email             string `json:"email"`
	}
	body, _ := io.ReadAll(resp.Body)
	if err := json.Unmarshal(body, &ui); err != nil {
		return "", "", "", err
	}
	if ui.Sub == "" {
		return "", "", "", errors.New("userinfo 缺少 sub")
	}
	if ui.Name == "" {
		ui.Name = ui.PreferredUsername
	}
	return ui.Sub, ui.Name, ui.Email, nil
}
