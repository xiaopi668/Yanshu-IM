// Package messaging 消息落库、seq 分配、投递。被 gateway 与 logic 共用。
package messaging

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"strconv"
	"sync"
	"time"

	"github.com/redis/go-redis/v9"

	"im/internal/hub"
	"im/internal/pb"
)

const pullLimit = 200

// Snowflake 简易雪花 ID：毫秒时间戳 << 22 | 节点 << 12 | 序列
type Snowflake struct {
	mu   sync.Mutex
	node int64
	last int64
	seq  int64
}

func NewSnowflake(node int64) *Snowflake { return &Snowflake{node: node & 0x3FF} }

func (s *Snowflake) Next() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	now := time.Now().UnixMilli()
	if now == s.last {
		s.seq = (s.seq + 1) & 0xFFF
		if s.seq == 0 {
			for now == s.last {
				time.Sleep(time.Millisecond)
				now = time.Now().UnixMilli()
			}
		}
	} else {
		s.seq = 0
	}
	s.last = now
	return strconv.FormatInt((now-1704038400000)<<22|s.node<<12|s.seq, 10)
}

type Service struct {
	DB  *sql.DB
	RDB *redis.Client
	Hub *hub.Hub
	ID  *Snowflake
}

func New(db *sql.DB, rdb *redis.Client, h *hub.Hub, node int64) *Service {
	return &Service{DB: db, RDB: rdb, Hub: h, ID: NewSnowflake(node)}
}

// NextSeq 会话内单调递增 seq（Redis INCR，原子）
func (s *Service) NextSeq(ctx context.Context, convID string) (uint64, error) {
	n, err := s.RDB.Incr(ctx, "im:seq:"+convID).Result()
	if err != nil {
		return 0, err
	}
	return uint64(n), nil
}

// MaxSeq 返回服务端某会话最新 seq；Redis 无记录时回落 DB
func (s *Service) MaxSeq(ctx context.Context, convID string) (uint64, error) {
	n, err := s.RDB.Get(ctx, "im:seq:"+convID).Uint64()
	if err == nil {
		return n, nil
	}
	if err != redis.Nil {
		return 0, err
	}
	var max sql.NullInt64
	err = s.DB.QueryRowContext(ctx,
		`SELECT MAX(seq) FROM message WHERE conversation_id=?`, convID).Scan(&max)
	if err != nil {
		return 0, err
	}
	return uint64(max.Int64), nil
}

// MaxSeqs 批量取用户所有会话的最新 seq（用于鉴权后的概览）
func (s *Service) MaxSeqs(ctx context.Context, uid string) (map[string]uint64, error) {
	rows, err := s.DB.QueryContext(ctx,
		`SELECT conversation_id FROM conversation_member WHERE uid=?`, uid)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[string]uint64{}
	for rows.Next() {
		var cid string
		if err := rows.Scan(&cid); err != nil {
			return nil, err
		}
		seq, err := s.MaxSeq(ctx, cid)
		if err != nil {
			return nil, err
		}
		out[cid] = seq
	}
	return out, rows.Err()
}

// Send 发送消息：幂等去重 → 分配 seq → 落库 → 在线投递。
// 返回 (serverMsgID, seq)。
func (s *Service) Send(ctx context.Context, fromUID string, m *pb.MsgSend) (string, uint64, error) {
	// 客户端重试幂等：同一 client_msg_id 直接返回已存结果
	if m.ClientMsgId != "" {
		var msgID string
		var seq int64
		err := s.DB.QueryRowContext(ctx,
			`SELECT server_msg_id, seq FROM message_client_map WHERE client_msg_id=? AND from_uid=?`,
			m.ClientMsgId, fromUID).Scan(&msgID, &seq)
		if err == nil {
			return msgID, uint64(seq), nil
		}
		if err != sql.ErrNoRows {
			return "", 0, err
		}
	}

	convID := m.ConversationId
	if err := s.assertMember(ctx, fromUID, convID); err != nil {
		return "", 0, err
	}

	seq, err := s.NextSeq(ctx, convID)
	if err != nil {
		return "", 0, err
	}
	msgID := s.ID.Next()
	sentAt := time.Now().UnixMilli()

	attJSON, _ := json.Marshal(m.Attachment)
	if m.Attachment == nil {
		attJSON = []byte("{}")
	}
	mentionJSON, _ := json.Marshal(m.MentionUids)

	tx, err := s.DB.BeginTx(ctx, nil)
	if err != nil {
		return "", 0, err
	}
	defer tx.Rollback()
	if _, err = tx.ExecContext(ctx,
		`INSERT INTO message(server_msg_id, conversation_id, seq, from_uid, msg_type, text, attachment, mention_uids, sent_at)
		 VALUES(?,?,?,?,?,?,?,?,?)`,
		msgID, convID, seq, fromUID, int(m.MsgType), m.Text, string(attJSON), string(mentionJSON), sentAt); err != nil {
		return "", 0, err
	}
	if m.ClientMsgId != "" {
		if _, err = tx.ExecContext(ctx,
			`INSERT INTO message_client_map(client_msg_id, from_uid, server_msg_id, seq) VALUES(?,?,?,?)`,
			m.ClientMsgId, fromUID, msgID, seq); err != nil {
			return "", 0, err
		}
	}
	if err = tx.Commit(); err != nil {
		return "", 0, err
	}

	notify := &pb.MsgNotify{
		ConversationId: convID,
		Seq:            seq,
		ServerMsgId:    msgID,
		FromUid:        fromUID,
		MsgType:        m.MsgType,
		Text:           m.Text,
		Attachment:     m.Attachment,
		SentAt:         sentAt,
		MentionUids:    m.MentionUids,
	}
	frame := &pb.Frame{Body: &pb.Frame_MsgNotify{MsgNotify: notify}}
	// 投递给会话所有成员（含发送者其他端）
	uids, err := s.members(ctx, convID)
	if err != nil {
		return msgID, seq, err
	}
	for _, uid := range uids {
		s.Hub.SendToUser(uid, frame)
	}
	return msgID, seq, nil
}

// Pull 拉补：返回 > afterSeq 的消息（升序，最多 pullLimit 条）
func (s *Service) Pull(ctx context.Context, uid, convID string, afterSeq uint64, limit uint32) (*pb.MsgPullResp, error) {
	if err := s.assertMember(ctx, uid, convID); err != nil {
		return nil, err
	}
	if limit == 0 || limit > pullLimit {
		limit = pullLimit
	}
	rows, err := s.DB.QueryContext(ctx,
		`SELECT server_msg_id, seq, from_uid, msg_type, text, attachment, mention_uids, sent_at
		 FROM message WHERE conversation_id=? AND seq>? ORDER BY seq ASC LIMIT ?`,
		convID, afterSeq, limit+1)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	resp := &pb.MsgPullResp{ConversationId: convID}
	var msgs []*pb.MsgNotify
	for rows.Next() {
		var msgID, fromUID, text, att, mention string
		var seq, sentAt int64
		var msgType int
		if err := rows.Scan(&msgID, &seq, &fromUID, &msgType, &text, &att, &mention, &sentAt); err != nil {
			return nil, err
		}
		n := &pb.MsgNotify{
			ConversationId: convID,
			ServerMsgId:    msgID,
			Seq:            uint64(seq),
			FromUid:        fromUID,
			MsgType:        pb.MsgType(msgType),
			Text:           text,
			SentAt:         sentAt,
		}
		if att != "" && att != "{}" {
			_ = json.Unmarshal([]byte(att), &n.Attachment)
		}
		if mention != "" && mention != "null" {
			_ = json.Unmarshal([]byte(mention), &n.MentionUids)
		}
		msgs = append(msgs, n)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	hasMore := false
	if len(msgs) > int(limit) {
		hasMore = true
		msgs = msgs[:limit]
	}
	resp.Msgs = msgs
	resp.HasMore = hasMore
	maxSeq, err := s.MaxSeq(ctx, convID)
	if err != nil {
		return nil, err
	}
	resp.MaxSeq = maxSeq
	return resp, nil
}

// MarkRead 已读上报：更新 read_seq 并同步给会话其他在线成员
func (s *Service) MarkRead(ctx context.Context, uid, convID string, upTo uint64) error {
	if _, err := s.DB.ExecContext(ctx,
		`UPDATE conversation_member SET read_seq=GREATEST(read_seq, ?) WHERE conversation_id=? AND uid=?`,
		upTo, convID, uid); err != nil {
		return err
	}
	uids, err := s.members(ctx, convID)
	if err != nil {
		return err
	}
	_ = uids // 已读回执通知在 M3 扩展；MVP 仅落库
	return nil
}

func (s *Service) assertMember(ctx context.Context, uid, convID string) error {
	var n int
	err := s.DB.QueryRowContext(ctx,
		`SELECT COUNT(*) FROM conversation_member WHERE conversation_id=? AND uid=?`, convID, uid).Scan(&n)
	if err != nil {
		return err
	}
	if n == 0 {
		return fmt.Errorf("not a member of conversation %s", convID)
	}
	return nil
}

func (s *Service) members(ctx context.Context, convID string) ([]string, error) {
	rows, err := s.DB.QueryContext(ctx,
		`SELECT uid FROM conversation_member WHERE conversation_id=?`, convID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []string
	for rows.Next() {
		var uid string
		if err := rows.Scan(&uid); err != nil {
			return nil, err
		}
		out = append(out, uid)
	}
	return out, rows.Err()
}
