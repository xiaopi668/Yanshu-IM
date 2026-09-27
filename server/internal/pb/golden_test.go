package pb

import (
	"flag"
	"os"
	"path/filepath"
	"testing"

	"google.golang.org/protobuf/proto"
)

var updateGolden = flag.Bool("update", false, "重新生成 proto/golden/*.bin")

// goldenDir 相对本包（server/internal/pb）到仓库根的 proto/golden
const goldenDir = "../../../proto/golden"

// goldenCases 协议样例，是双端一致性的锚点：
//   - Go 侧：本测试编码/解码并比对 proto/golden/<name>.bin
//   - 客户端：ProtoGoldenTest 解码同一批文件，校验手写编解码没有字段号漂移
//
// 新增或修改字段时执行 ./proto/gen.sh --update，并同步更新客户端期望值。
func goldenCases() []goldenCase {
	return []goldenCase{
		{Name: "heartbeat", Frame: &Frame{Body: &Frame_Heartbeat{Heartbeat: &Heartbeat{}}}},
		{Name: "auth_req", Frame: &Frame{Body: &Frame_AuthReq{AuthReq: &AuthReq{
			Token: "jwt-token-abc", DeviceId: "dev-1", Platform: "desktop",
		}}}},
		{Name: "auth_resp_ok", Frame: &Frame{Body: &Frame_AuthResp{AuthResp: &AuthResp{
			Ok: true,
			// map 字段 + 大于 2^53 的 seq，校验双端对 uint64 的处理
			MaxSeqs: map[string]uint64{"s_1_2": 42, "g_100": 9007199254740993},
		}}}},
		{Name: "auth_resp_fail", Frame: &Frame{Body: &Frame_AuthResp{AuthResp: &AuthResp{
			Ok: false, Reason: "account disabled",
		}}}},
		{Name: "msg_send_text", Frame: &Frame{Body: &Frame_MsgSend{MsgSend: &MsgSend{
			ClientMsgId: "cm-1", ConversationId: "s_1_2", MsgType: MsgType_MSG_TEXT, Text: "你好，雁书",
		}}}},
		{Name: "msg_send_image", Frame: &Frame{Body: &Frame_MsgSend{MsgSend: &MsgSend{
			ClientMsgId: "cm-2", ConversationId: "g_100", MsgType: MsgType_MSG_IMAGE,
			Attachment: &Attachment{
				Url: "http://minio:9000/im-attachments/a.png", Name: "a.png",
				Size: 12345, Mime: "image/png", Width: 100, Height: 80,
			},
			MentionUids: []string{"u1", "u2"},
		}}}},
		{Name: "msg_ack", Frame: &Frame{Body: &Frame_MsgAck{MsgAck: &MsgAck{
			ClientMsgId: "cm-1", Seq: 42, ServerMsgId: "1700000000000000001", ConversationId: "s_1_2",
		}}}},
		{Name: "msg_notify_text", Frame: &Frame{Body: &Frame_MsgNotify{MsgNotify: &MsgNotify{
			ConversationId: "s_1_2", Seq: 42, ServerMsgId: "1700000000000000001",
			FromUid: "u1", MsgType: MsgType_MSG_TEXT, Text: "hi", SentAt: 1700000000000,
		}}}},
		{Name: "msg_notify_image", Frame: &Frame{Body: &Frame_MsgNotify{MsgNotify: &MsgNotify{
			ConversationId: "g_100", Seq: 43, ServerMsgId: "1700000000000000002",
			FromUid: "u2", MsgType: MsgType_MSG_IMAGE, SentAt: 1700000000123,
			Attachment: &Attachment{Url: "http://minio:9000/im-attachments/b.jpg", Name: "b.jpg",
				Size: 6789, Mime: "image/jpeg", Width: 640, Height: 480},
			MentionUids: []string{"u3"},
		}}}},
		{Name: "msg_pull_req", Frame: &Frame{Body: &Frame_MsgPullReq{MsgPullReq: &MsgPullReq{
			ConversationId: "s_1_2", AfterSeq: 42, Limit: 200,
		}}}},
		{Name: "msg_pull_resp", Frame: &Frame{Body: &Frame_MsgPullResp{MsgPullResp: &MsgPullResp{
			ConversationId: "s_1_2", HasMore: true, MaxSeq: 44,
			Msgs: []*MsgNotify{
				{ConversationId: "s_1_2", Seq: 43, ServerMsgId: "1700000000000000002",
					FromUid: "u2", MsgType: MsgType_MSG_SYSTEM, Text: `{"type":"member_added"}`, SentAt: 1700000000123},
				{ConversationId: "s_1_2", Seq: 44, ServerMsgId: "1700000000000000003",
					FromUid: "u1", MsgType: MsgType_MSG_FILE, Text: "", SentAt: 1700000000456,
					Attachment: &Attachment{Name: "report.pdf", Size: 1024, Mime: "application/pdf"}},
			},
		}}}},
		{Name: "msg_read", Frame: &Frame{Body: &Frame_MsgRead{MsgRead: &MsgRead{
			ConversationId: "s_1_2", UpToSeq: 44,
		}}}},
		{Name: "call_invite", Frame: &Frame{Body: &Frame_CallSignal{CallSignal: &CallSignal{
			CallId: "ab12cd34", Event: CallEventType_CALL_INVITE, Text: "u2",
		}}}},
		{Name: "call_accept", Frame: &Frame{Body: &Frame_CallSignal{CallSignal: &CallSignal{
			CallId: "ab12cd34", Event: CallEventType_CALL_ACCEPT,
			RoomName: "call-ab12cd34", LivekitToken: "eyJhbGciOiJIUzI1NiJ9.sig",
		}}}},
		{Name: "call_timeout", Frame: &Frame{Body: &Frame_CallSignal{CallSignal: &CallSignal{
			CallId: "ab12cd34", Event: CallEventType_CALL_TIMEOUT,
		}}}},
		{Name: "contact_request", Frame: &Frame{Body: &Frame_ContactEvent{ContactEvent: &ContactEvent{
			Type: "request", RequestId: "r-1", FromUid: "u9", Yid: "alice01",
			Nickname: "Alice", Message: "我是 Alice",
		}}}},
		{Name: "contact_accepted", Frame: &Frame{Body: &Frame_ContactEvent{ContactEvent: &ContactEvent{
			Type: "accepted", RequestId: "r-1", FromUid: "u9", Yid: "alice01", Nickname: "Alice",
		}}}},
	}
}

type goldenCase struct {
	Name  string
	Frame *Frame
}

// TestGolden 编解码与 proto/golden/*.bin 比对；
// 改协议后用 ./proto/gen.sh --update 重建。
func TestGolden(t *testing.T) {
	for _, c := range goldenCases() {
		c := c
		t.Run(c.Name, func(t *testing.T) {
			path := filepath.Join(goldenDir, c.Name+".bin")

			got, err := (proto.MarshalOptions{Deterministic: true}).Marshal(c.Frame)
			if err != nil {
				t.Fatalf("marshal: %v", err)
			}

			if *updateGolden {
				if err := os.MkdirAll(goldenDir, 0o755); err != nil {
					t.Fatal(err)
				}
				if err := os.WriteFile(path, got, 0o644); err != nil {
					t.Fatal(err)
				}
				t.Logf("已更新 %s (%d bytes)", path, len(got))
				return
			}

			want, err := os.ReadFile(path)
			if err != nil {
				t.Fatalf("读取 golden 失败（先执行 ./proto/gen.sh --update）: %v", err)
			}
			if string(got) != string(want) {
				t.Errorf("编码结果与 golden 不一致\n got: %x\nwant: %x", got, want)
			}

			// 解码回环：确认 oneof 分支没有被错认
			var back Frame
			if err := proto.Unmarshal(want, &back); err != nil {
				t.Fatalf("unmarshal: %v", err)
			}
			if !proto.Equal(c.Frame, &back) {
				t.Errorf("回环不一致:\n got: %v\nwant: %v", &back, c.Frame)
			}
			// 至少能识别出是哪个 oneof 分支
			if back.GetBody() == nil || bodyKind(&back) != bodyKind(c.Frame) {
				t.Errorf("oneof 分支被破坏: %v", back.GetBody())
			}
		})
	}
}

// TestGoldenFilesNoExtra 保证 golden 目录里没有遗留的过期样例
func TestGoldenFilesNoExtra(t *testing.T) {
	if *updateGolden {
		t.Skip("update 模式")
	}
	entries, err := os.ReadDir(goldenDir)
	if err != nil {
		t.Fatalf("读取 %s 失败: %v", goldenDir, err)
	}
	known := map[string]bool{}
	for _, c := range goldenCases() {
		known[c.Name+".bin"] = true
	}
	for _, e := range entries {
		if e.IsDir() {
			continue
		}
		if !known[e.Name()] {
			t.Errorf("golden 目录存在过期样例 %s，请清理或补进 goldenCases", e.Name())
		}
	}
}

func bodyKind(f *Frame) string {
	switch f.GetBody().(type) {
	case *Frame_Heartbeat:
		return "heartbeat"
	case *Frame_AuthReq:
		return "auth_req"
	case *Frame_AuthResp:
		return "auth_resp"
	case *Frame_MsgSend:
		return "msg_send"
	case *Frame_MsgAck:
		return "msg_ack"
	case *Frame_MsgNotify:
		return "msg_notify"
	case *Frame_MsgPullReq:
		return "msg_pull_req"
	case *Frame_MsgPullResp:
		return "msg_pull_resp"
	case *Frame_MsgRead:
		return "msg_read"
	case *Frame_CallSignal:
		return "call_signal"
	case *Frame_ContactEvent:
		return "contact_event"
	}
	return "unknown"
}
