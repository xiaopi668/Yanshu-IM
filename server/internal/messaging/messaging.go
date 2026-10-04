// Package messaging 消息落库、seq 分配、投递。被 gateway 与 logic 共用。
package messaging

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"strconv"
	"sync"
	"time"

	"github.com/go-sql-driver/mysql"
	"github.com/redis/go-redis/v9"

	"im/internal/hub"
	"im/internal/metrics"
	"im/internal/pb"
	"im/internal/storage"
)

const pullLimit = 200

// ErrNotMember 调用方不是会话成员。
// 单独定义成哨兵错误，让上层（gateway）能把"不是成员"和其它失败区分开，
// 回给客户端一个可判定的错误码，而不是只写日志。
var ErrNotMember = errors.New("not a member of conversation")

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

// seqScript 先把计数器抬到 DB 下界再 INCR，保证 Redis 被清空/重建后不会从 1 重新计数
// 而与 message 表主键 (conversation_id, seq) 冲突。
const seqScript = `
local key = KEYS[1]
local floor = tonumber(ARGV[1])
local cur = redis.call('GET', key)
if (not cur) or tonumber(cur) < floor then
  redis.call('SET', key, floor)
end
return redis.call('INCR', key)
`

// NextSeq 会话内单调递增 seq（Redis INCR，原子）。
// 下界取 DB 中该会话的 MAX(seq)，因此 Redis 数据丢失也能自愈。
func (s *Service) NextSeq(ctx context.Context, convID string) (uint64, error) {
	floor, err := s.maxSeqInDB(ctx, convID)
	if err != nil {
		return 0, err
	}
	n, err := s.RDB.Eval(ctx, seqScript, []string{"im:seq:" + convID}, floor).Int64()
	if err != nil {
		return 0, err
	}
	return uint64(n), nil
}

// maxSeqInDB 该会话在库中的最大 seq（无消息时为 0）
func (s *Service) maxSeqInDB(ctx context.Context, convID string) (uint64, error) {
	var max sql.NullInt64
	err := s.DB.QueryRowContext(ctx,
		`SELECT MAX(seq) FROM message WHERE conversation_id=?`, convID).Scan(&max)
	if err != nil {
		return 0, err
	}
	if max.Int64 < 0 {
		return 0, nil
	}
	return uint64(max.Int64), nil
}

// MaxSeq 返回服务端某会话最新 seq；取 Redis 与 DB 的较大者
// （Redis 可能因分配后插入失败而超前，也可能因重建而落后）
func (s *Service) MaxSeq(ctx context.Context, convID string) (uint64, error) {
	dbMax, err := s.maxSeqInDB(ctx, convID)
	if err != nil {
		return 0, err
	}
	n, err := s.RDB.Get(ctx, "im:seq:"+convID).Uint64()
	if err == nil && n > dbMax {
		return n, nil
	}
	if err != nil && err != redis.Nil {
		return 0, err
	}
	return dbMax, nil
}

// MaxSeqs 批量取用户所有会话的最新 seq（用于鉴权后的概览）。
// DB 侧一次 GROUP BY 取回，避免逐会话查询。
func (s *Service) MaxSeqs(ctx context.Context, uid string) (map[string]uint64, error) {
	rows, err := s.DB.QueryContext(ctx,
		`SELECT cm.conversation_id, IFNULL(MAX(m.seq),0)
		 FROM conversation_member cm
		 LEFT JOIN message m ON m.conversation_id = cm.conversation_id
		 WHERE cm.uid=? GROUP BY cm.conversation_id`, uid)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	type pair struct {
		cid string
		db  uint64
	}
	var list []pair
	for rows.Next() {
		var p pair
		if err := rows.Scan(&p.cid, &p.db); err != nil {
			return nil, err
		}
		list = append(list, p)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	out := map[string]uint64{}
	if len(list) == 0 {
		return out, nil
	}
	// Redis 侧一次 MGET 取回，避免逐会话往返
	keys := make([]string, len(list))
	for i, p := range list {
		keys[i] = "im:seq:" + p.cid
	}
	vals, err := s.RDB.MGet(ctx, keys...).Result()
	if err != nil && err != redis.Nil {
		return nil, err
	}
	for i, p := range list {
		// Redis 可能超前（分配后插入失败）也可能落后（重建），取较大者
		max := p.db
		if i < len(vals) {
			if n, err := strconv.ParseUint(toString(vals[i]), 10, 64); err == nil && n > max {
				max = n
			}
		}
		out[p.cid] = max
	}
	return out, nil
}

// toString 把 MGET 的结果转成字符串（nil 表示 key 不存在）
func toString(v any) string {
	if v == nil {
		return ""
	}
	if s, ok := v.(string); ok {
		return s
	}
	return fmt.Sprint(v)
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

	sentAt := time.Now().UnixMilli()

	attJSON, _ := json.Marshal(m.Attachment)
	if m.Attachment == nil {
		attJSON = []byte("{}")
	}
	// 附件只记录对象 key（客户端把 key 放在 Attachment.Url 字段里），
	// 供下载时做「这个 key 属于调用方可见的会话吗」的授权判断。
	attKey := ""
	if m.Attachment != nil && storage.ValidObjectKey(m.Attachment.GetUrl()) {
		attKey = m.Attachment.GetUrl()
	}
	mentionJSON, _ := json.Marshal(m.MentionUids)

	// 分配 seq 并落库。
	// message 有两个唯一键：PK(conversation_id, seq) 与 uk_msg_id(server_msg_id)，
	// 两者都要能自愈，因此**每轮重试都重新生成 msgID**：
	//   - 撞 PK：Redis 计数器落后于 DB（如 Redis 被清空），NextSeq 下轮以 DB MAX 为下界重取；
	//   - 撞 uk_msg_id：多副本下雪花 ID 可能重复，必须换 ID 而不是拿同一个 ID 反复重试。
	var seq uint64
	for attempt := 0; ; attempt++ {
		msgID := s.ID.Next()
		var err error
		seq, err = s.NextSeq(ctx, convID)
		if err != nil {
			return "", 0, err
		}
		stage, err := s.insertMessage(ctx, fromUID, m, msgID, seq, sentAt, string(attJSON), string(mentionJSON), attKey)
		if err == nil {
			return s.finishSend(ctx, fromUID, m, msgID, seq, sentAt)
		}
		if attempt >= 3 {
			return "", 0, err
		}
		if !isDupKey(err) {
			return "", 0, err
		}
		if stage == stageClientMap {
			// 并发重试撞了 client_msg_id 幂等键：回读已存结果，保证幂等语义
			if oldID, oldSeq, ok := s.lookupClientMsg(ctx, fromUID, m.ClientMsgId); ok {
				return oldID, oldSeq, nil
			}
			return "", 0, err
		}
		// stageMessage：seq 或 server_msg_id 冲突，重试重新取号 + 换 ID
		metrics.Inc("im_msg_persist_retries_total")
	}
}

// finishSend 落库成功后组帧并投递给会话全部成员（含发送者其他端），跨进程只广播一次。
// 投递失败时消息已在库里，返回 err 让调用方不回 ACK，客户端重试会命中幂等键拿到同一结果。
func (s *Service) finishSend(ctx context.Context, fromUID string, m *pb.MsgSend, msgID string, seq uint64, sentAt int64) (string, uint64, error) {
	notify := &pb.MsgNotify{
		ConversationId: m.ConversationId,
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
	uids, err := s.members(ctx, m.ConversationId)
	if err != nil {
		return msgID, seq, err
	}
	s.Hub.SendToUsers(uids, frame)
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
	msgs, err := scanMsgs(rows, convID)
	if err != nil {
		return nil, err
	}

	resp := &pb.MsgPullResp{ConversationId: convID}
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

// PullBefore 向上翻历史：返回 < beforeSeq 的消息（升序输出）。
// beforeSeq 为 0 时从最新一条往回取。返回的 hasMore 表示更早还有消息。
func (s *Service) PullBefore(ctx context.Context, uid, convID string, beforeSeq uint64, limit uint32) ([]*pb.MsgNotify, bool, error) {
	if err := s.assertMember(ctx, uid, convID); err != nil {
		return nil, false, err
	}
	if limit == 0 || limit > pullLimit {
		limit = pullLimit
	}
	if beforeSeq == 0 { // 从最新往回翻
		maxSeq, err := s.MaxSeq(ctx, convID)
		if err != nil {
			return nil, false, err
		}
		beforeSeq = maxSeq + 1
	}
	rows, err := s.DB.QueryContext(ctx,
		`SELECT server_msg_id, seq, from_uid, msg_type, text, attachment, mention_uids, sent_at
		 FROM message WHERE conversation_id=? AND seq<? ORDER BY seq DESC LIMIT ?`,
		convID, beforeSeq, limit+1)
	if err != nil {
		return nil, false, err
	}
	msgs, err := scanMsgs(rows, convID)
	if err != nil {
		return nil, false, err
	}
	hasMore := false
	if len(msgs) > int(limit) {
		hasMore = true
		msgs = msgs[:limit]
	}
	// 降序取回，输出时转升序，保持与 Pull 一致
	for i, j := 0, len(msgs)-1; i < j; i, j = i+1, j-1 {
		msgs[i], msgs[j] = msgs[j], msgs[i]
	}
	return msgs, hasMore, nil
}

// scanMsgs 读取 message 查询结果；调用方负责关闭 rows
func scanMsgs(rows *sql.Rows, convID string) ([]*pb.MsgNotify, error) {
	defer rows.Close()
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
	return msgs, rows.Err()
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

// BuildContactEvent 构建通讯录事件帧（logic 通过 hub 推送）
func (s *Service) BuildContactEvent(typ, reqID, fromUID, yid, nickname, message string) *pb.Frame {
	return &pb.Frame{Body: &pb.Frame_ContactEvent{ContactEvent: &pb.ContactEvent{
		Type: typ, RequestId: reqID, FromUid: fromUID, Yid: yid, Nickname: nickname, Message: message,
	}}}
}

// 落库阶段标识，用于区分撞的是哪张表的唯一键
const (
	stageMessage = iota
	stageClientMap
)

// insertMessage 在一个事务里写入 message 与幂等映射，返回失败所在的阶段。
// attKey 为附件对象 key（无附件时为空串），单独成列并建索引，供下载授权判断使用。
func (s *Service) insertMessage(ctx context.Context, fromUID string, m *pb.MsgSend,
	msgID string, seq uint64, sentAt int64, attJSON, mentionJSON, attKey string) (int, error) {
	tx, err := s.DB.BeginTx(ctx, nil)
	if err != nil {
		return stageMessage, err
	}
	defer tx.Rollback()
	if _, err = tx.ExecContext(ctx,
		`INSERT INTO message(server_msg_id, conversation_id, seq, from_uid, msg_type, text, attachment, mention_uids, sent_at, attachment_key)
		 VALUES(?,?,?,?,?,?,?,?,?,?)`,
		msgID, m.ConversationId, seq, fromUID, int(m.MsgType), m.Text, attJSON, mentionJSON, sentAt, nullIfEmpty(attKey)); err != nil {
		return stageMessage, err
	}
	if m.ClientMsgId != "" {
		if _, err = tx.ExecContext(ctx,
			`INSERT INTO message_client_map(client_msg_id, from_uid, server_msg_id, seq) VALUES(?,?,?,?)`,
			m.ClientMsgId, fromUID, msgID, seq); err != nil {
			return stageClientMap, err
		}
	}
	if err = tx.Commit(); err != nil {
		return stageMessage, err
	}
	return stageMessage, nil
}

// nullIfEmpty 空串写成 NULL，避免 attachment_key 索引里塞满空串
func nullIfEmpty(s string) any {
	if s == "" {
		return nil
	}
	return s
}

// lookupClientMsg 幂等键已存在时回读其结果
func (s *Service) lookupClientMsg(ctx context.Context, fromUID, clientMsgID string) (string, uint64, bool) {
	if clientMsgID == "" {
		return "", 0, false
	}
	var msgID string
	var seq int64
	err := s.DB.QueryRowContext(ctx,
		`SELECT server_msg_id, seq FROM message_client_map WHERE client_msg_id=? AND from_uid=?`,
		clientMsgID, fromUID).Scan(&msgID, &seq)
	if err != nil {
		return "", 0, false
	}
	return msgID, uint64(seq), true
}

// isDupKey 判断是否为唯一键冲突（MySQL 1062）
func isDupKey(err error) bool {
	var me *mysql.MySQLError
	return errors.As(err, &me) && me.Number == 1062
}

func (s *Service) assertMember(ctx context.Context, uid, convID string) error {
	var n int
	err := s.DB.QueryRowContext(ctx,
		`SELECT COUNT(*) FROM conversation_member WHERE conversation_id=? AND uid=?`, convID, uid).Scan(&n)
	if err != nil {
		return err
	}
	if n == 0 {
		return fmt.Errorf("%w: %s", ErrNotMember, convID)
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
