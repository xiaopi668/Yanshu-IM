// Package call 1v1 音视频通话信令。
// 媒体面由自托管 LiveKit SFU 承载；本包只处理 invite/accept/reject 状态机并签发 room token。
package call

import (
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"sync"
	"time"

	lkauth "github.com/livekit/protocol/auth"
)

var ErrBusy = fmt.Errorf("peer busy")

const ringTimeout = 60 * time.Second

// Manager 通话状态机（单机版；多实例时可挪 Redis）
type Manager struct {
	mu sync.Mutex
	// callID -> 进行中的通话
	calls map[string]*Session
	// 被叫UID -> 待接听 invite
	pending map[string]*Invite
	// 签发 LiveKit token 的密钥
	apiKey    string
	apiSecret string
}

type Invite struct {
	CallID   string
	RoomName string
	FromUID  string
	ToUID    string
	Created  time.Time
}

type Session struct {
	CallID   string
	RoomName string
	A, B     string
	Created  time.Time
}

func NewManager(apiKey, apiSecret string) *Manager {
	return &Manager{
		calls:     map[string]*Session{},
		pending:   map[string]*Invite{},
		apiKey:    apiKey,
		apiSecret: apiSecret,
	}
}

func genID() string {
	b := make([]byte, 8)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

// StartInvite 发起：登记 pending。若被叫已有待接听邀请，返回 busy。
func (m *Manager) StartInvite(fromUID, toUID string) (*Invite, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if _, busy := m.pending[toUID]; busy {
		return nil, ErrBusy
	}
	callID := genID()
	inv := &Invite{
		CallID:   callID,
		RoomName: "call-" + callID,
		FromUID:  fromUID,
		ToUID:    toUID,
		Created:  time.Now(),
	}
	m.pending[toUID] = inv
	return inv, nil
}

// Accept 被叫接听：建立通话会话，返回双方各自的 LiveKit token
func (m *Manager) Accept(callID, byUID string) (tokenA, tokenB string, ok bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	inv, has := m.pending[byUID]
	if !has || inv.CallID != callID {
		return "", "", false
	}
	delete(m.pending, byUID)
	s := &Session{CallID: callID, RoomName: inv.RoomName, A: inv.FromUID, B: byUID, Created: time.Now()}
	m.calls[callID] = s
	tokenA = m.issue(inv.FromUID, inv.RoomName)
	tokenB = m.issue(byUID, inv.RoomName)
	return tokenA, tokenB, true
}

func (m *Manager) Cancel(callID string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	for uid, inv := range m.pending {
		if inv.CallID == callID {
			delete(m.pending, uid)
		}
	}
}

func (m *Manager) InviteOf(toUID string) *Invite {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.pending[toUID]
}

func (m *Manager) Session(callID string) *Session {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.calls[callID]
}

// PollExpired 清理超时未接听的邀请，返回被取消的邀请（供 gateway 推 CALL_TIMEOUT）
func (m *Manager) PollExpired() []*Invite {
	m.mu.Lock()
	defer m.mu.Unlock()
	var cancelled []*Invite
	for uid, inv := range m.pending {
		if time.Since(inv.Created) > ringTimeout {
			delete(m.pending, uid)
			cancelled = append(cancelled, inv)
		}
	}
	return cancelled
}

// PollStaleSessions 清理长时间未挂断的僵尸会话（如双方都掉线），返回被清理的会话
func (m *Manager) PollStaleSessions(maxAge time.Duration) []*Session {
	m.mu.Lock()
	defer m.mu.Unlock()
	var stale []*Session
	for id, s := range m.calls {
		if time.Since(s.Created) > maxAge {
			delete(m.calls, id)
			stale = append(stale, s)
		}
	}
	return stale
}

// End 挂断：释放会话，避免 calls 表常驻内存
func (m *Manager) End(callID string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	delete(m.calls, callID)
}

// issue 签发 LiveKit room join token
func (m *Manager) issue(identity, room string) string {
	at := lkauth.NewAccessToken(m.apiKey, m.apiSecret)
	at.SetIdentity(identity).
		SetValidFor(24 * time.Hour)
	grant := &lkauth.VideoGrant{Room: room}
	at.SetVideoGrant(grant)
	jwt, err := at.ToJWT()
	if err != nil {
		return ""
	}
	return jwt
}
