package pb

import (
	"os"
	"path/filepath"
	"testing"

	"google.golang.org/protobuf/proto"
)

// TestClientGolden 用 protoc 生成的实现反向解码客户端编码的上行帧，
// 校验手写编码器（client/shared/.../proto/Frames.kt）没有写错字段号。
// 文件由 UPDATE_CLIENT_GOLDEN=1 gradle :shared:desktopTest 生成。
func TestClientGolden(t *testing.T) {
	dir := filepath.Join(goldenDir, "client")
	cases := []string{"auth_req", "msg_send_text", "msg_send_image", "msg_pull_req", "msg_read"}
	for _, name := range cases {
		name := name
		t.Run(name, func(t *testing.T) {
			path := filepath.Join(dir, name+".bin")
			data, err := os.ReadFile(path)
			if err != nil {
				t.Fatalf("读取客户端 golden 失败（先执行 UPDATE_CLIENT_GOLDEN=1 gradle :shared:desktopTest）: %v", err)
			}
			var f Frame
			if err := proto.Unmarshal(data, &f); err != nil {
				t.Fatalf("客户端编码的帧无法被 protoc 实现解析: %v", err)
			}
			if f.GetBody() == nil {
				t.Fatal("oneof body 为空：客户端写错了 Frame 字段号")
			}
			checkClientFrame(t, name, &f)
		})
	}
}

func checkClientFrame(t *testing.T, name string, f *Frame) {
	t.Helper()
	switch name {
	case "auth_req":
		a := f.GetAuthReq()
		if a == nil {
			t.Fatalf("oneof = %T, want auth_req", f.GetBody())
		}
		if a.Token != "jwt-token-abc" || a.DeviceId != "dev-1" || a.Platform != "desktop" {
			t.Fatalf("auth_req 字段错位: %+v", a)
		}
	case "msg_send_text":
		m := f.GetMsgSend()
		if m == nil {
			t.Fatalf("oneof = %T, want msg_send", f.GetBody())
		}
		if m.ClientMsgId != "cm-1" || m.ConversationId != "s_1_2" ||
			m.MsgType != MsgType_MSG_TEXT || m.Text != "你好，雁书" {
			t.Fatalf("msg_send_text 字段错位: %+v", m)
		}
		if m.Attachment != nil {
			t.Fatalf("文本消息不应带 attachment: %+v", m.Attachment)
		}
	case "msg_send_image":
		m := f.GetMsgSend()
		if m == nil {
			t.Fatalf("oneof = %T, want msg_send", f.GetBody())
		}
		if m.ClientMsgId != "cm-2" || m.ConversationId != "g_100" || m.MsgType != MsgType_MSG_IMAGE {
			t.Fatalf("msg_send_image 字段错位: %+v", m)
		}
		att := m.Attachment
		if att == nil {
			t.Fatal("attachment 丢失")
		}
		if att.Url != "http://minio:9000/im-attachments/a.png" || att.Name != "a.png" ||
			att.Size != 12345 || att.Mime != "image/png" || att.Width != 100 || att.Height != 80 {
			t.Fatalf("attachment 字段错位: %+v", att)
		}
	case "msg_pull_req":
		p := f.GetMsgPullReq()
		if p == nil {
			t.Fatalf("oneof = %T, want msg_pull_req", f.GetBody())
		}
		if p.ConversationId != "s_1_2" || p.AfterSeq != 42 || p.Limit != 200 {
			t.Fatalf("msg_pull_req 字段错位: %+v", p)
		}
	case "msg_read":
		r := f.GetMsgRead()
		if r == nil {
			t.Fatalf("oneof = %T, want msg_read", f.GetBody())
		}
		if r.ConversationId != "s_1_2" || r.UpToSeq != 44 {
			t.Fatalf("msg_read 字段错位: %+v", r)
		}
	default:
		t.Fatalf("未知用例 %s", name)
	}
}
