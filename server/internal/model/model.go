package model

// User 用户表
type User struct {
	UID          string `db:"uid"` // 用户唯一 ID（雪花）
	Username     string `db:"username"`
	PasswordHash string `db:"password_hash"`
	Nickname     string `db:"nickname"`
	AvatarURL    string `db:"avatar_url"`
	CreatedAt    int64  `db:"created_at"`
}

// Friend 好友关系（双向两行）
type Friend struct {
	OwnerUID  string `db:"owner_uid"`
	FriendUID string `db:"friend_uid"`
	Remark    string `db:"remark"`
	CreatedAt int64  `db:"created_at"`
}

// Conversation 会话
type Conversation struct {
	ConversationID string `db:"conversation_id"` // 单聊: "s_{uidA}_{uidB}" (uidA<uidB) 群聊: "g_{snowflake}"
	Type           string `db:"type"`            // "single" / "group"
	CreatedAt      int64  `db:"created_at"`
}

// ConversationMember 会话成员
type ConversationMember struct {
	ConversationID string `db:"conversation_id"`
	UID            string `db:"uid"`
	Role           string `db:"role"` // owner / member
	JoinedAt       int64  `db:"joined_at"`
	ReadSeq        uint64 `db:"read_seq"` // 已读到的 seq
}

// Group 群组信息
type Group struct {
	GroupID     string `db:"group_id"`
	Name        string `db:"name"`
	OwnerUID    string `db:"owner_uid"`
	AvatarURL   string `db:"avatar_url"`
	CreatedAt   int64  `db:"created_at"`
}

// Message 消息（历史表）
type Message struct {
	ServerMsgID   string `db:"server_msg_id"`
	ConversationID string `db:"conversation_id"`
	Seq           uint64 `db:"seq"`
	FromUID       string `db:"from_uid"`
	MsgType       int    `db:"msg_type"`
	Text          string `db:"text"`
	Attachment    string `db:"attachment"` // JSON
	MentionUIDs   string `db:"mention_uids"` // JSON 数组
	SentAt        int64  `db:"sent_at"`
}
