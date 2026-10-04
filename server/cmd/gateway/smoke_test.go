//go:build smoke

// 冒烟测试：需要本机已经起好 logic(:10002) / gateway(:10001) / admin(:10003)
// 与 MySQL+Redis。用于验证跨进程投递（Redis pub/sub 总线）、seq 分配、
// admin 服务可启动、历史翻页等改动在真实链路上的表现。
//
//	go test -tags smoke ./cmd/gateway -run TestSmoke -v -timeout 120s
package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"os"
	"strings"
	"testing"
	"time"

	"github.com/gorilla/websocket"
	"google.golang.org/protobuf/proto"

	"im/internal/pb"
)

// 端点可用环境变量覆盖：本机 10001/10002 常被别的调试实例占着，
// 覆盖后可把被测实例起在备用端口上跑同一套冒烟用例。
var (
	logicURL = envOr("SMOKE_LOGIC_URL", "http://127.0.0.1:10002")
	wsURL    = envOr("SMOKE_WS_URL", "ws://127.0.0.1:10001/ws")
	adminURL = envOr("SMOKE_ADMIN_URL", "http://127.0.0.1:10003")
)

func envOr(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

// TestSmokeErrorFrame 验证「任何 C->S 请求失败都必须回一帧」这条契约。
// 这是"静默丢消息"的根因：旧实现里发送失败只写服务端日志、不回应答，
// 客户端只能永远停在"发送中"，既不知道失败也不会重发。
func TestSmokeErrorFrame(t *testing.T) {
	name := fmt.Sprintf("smokeerr%d", time.Now().UnixNano()%1_000_000_000)
	code, reg := postJSON(t, logicURL+"/v1/register", "", map[string]string{"username": name, "password": "secret1"})
	if code != 200 {
		t.Fatalf("注册失败: %d %v", code, reg)
	}
	tok, _ := reg["token"].(string)
	if tok == "" {
		t.Fatalf("注册未返回 token: %v", reg)
	}
	c := dialAuthed(t, tok)
	defer c.conn.Close()

	// 1) 给一个自己不是成员的会话发消息 —— 必须回 not_member，并带上 client_msg_id
	clientMsgID := "smoke-err-" + name
	c.send(&pb.Frame{Body: &pb.Frame_MsgSend{MsgSend: &pb.MsgSend{
		ClientMsgId: clientMsgID, ConversationId: "s_not_mine_not_mine",
		MsgType: pb.MsgType_MSG_TEXT, Text: "x",
	}}})
	f := c.waitFor(5*time.Second, "MsgSend 失败应答", func(f *pb.Frame) bool { return f.GetError() != nil })
	if e := f.GetError(); e.Code != "not_member" || e.RefClientMsgId != clientMsgID {
		t.Fatalf("发送失败应答不符合预期: code=%q ref=%q msg=%q", e.Code, e.RefClientMsgId, e.Message)
	}

	// 2) 拉一个自己不是成员的会话 —— 同样要有应答
	c.send(&pb.Frame{Body: &pb.Frame_MsgPullReq{MsgPullReq: &pb.MsgPullReq{
		ConversationId: "s_not_mine_not_mine", AfterSeq: 0, Limit: 10,
	}}})
	f = c.waitFor(5*time.Second, "MsgPullReq 失败应答", func(f *pb.Frame) bool { return f.GetError() != nil })
	if e := f.GetError(); e.Code != "not_member" {
		t.Fatalf("拉取失败应答不符合预期: code=%q msg=%q", e.Code, e.Message)
	}

	// 3) 服务端不处理的帧类型也不能石沉大海
	c.send(&pb.Frame{Body: &pb.Frame_MsgAck{MsgAck: &pb.MsgAck{ClientMsgId: "x"}}})
	f = c.waitFor(5*time.Second, "未知帧应答", func(f *pb.Frame) bool { return f.GetError() != nil })
	if e := f.GetError(); e.Code != "unsupported" {
		t.Fatalf("未知帧应答不符合预期: code=%q msg=%q", e.Code, e.Message)
	}
}

func postJSON(t *testing.T, url, token string, body any) (int, map[string]any) {
	t.Helper()
	return postJSONHdr(t, url, token, body, nil)
}

func postJSONHdr(t *testing.T, url, token string, body any, hdr map[string]string) (int, map[string]any) {
	t.Helper()
	var rdr io.Reader
	if body != nil {
		b, _ := json.Marshal(body)
		rdr = bytes.NewReader(b)
	}
	req, _ := http.NewRequest(http.MethodPost, url, rdr)
	req.Header.Set("Content-Type", "application/json")
	for k, v := range hdr {
		req.Header.Set(k, v)
	}
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	resp, err := (&http.Client{Timeout: 10 * time.Second}).Do(req)
	if err != nil {
		t.Fatalf("POST %s: %v", url, err)
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)
	out := map[string]any{}
	_ = json.Unmarshal(raw, &out)
	return resp.StatusCode, out
}

func getJSON(t *testing.T, url, token string) (int, map[string]any) {
	t.Helper()
	req, _ := http.NewRequest(http.MethodGet, url, nil)
	if token != "" {
		req.Header.Set("Authorization", "Bearer "+token)
	}
	resp, err := (&http.Client{Timeout: 10 * time.Second}).Do(req)
	if err != nil {
		t.Fatalf("GET %s: %v", url, err)
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)
	out := map[string]any{}
	_ = json.Unmarshal(raw, &out)
	return resp.StatusCode, out
}

// wsClient 一条已鉴权的 WS 连接。
// gorilla/websocket 一旦读出错误（含超时）就永久处于 failed 状态，再次读取会 panic，
// 所以这里起一个常驻读协程把帧泵进 channel，测试侧只做 select，避免重复读。
type wsClient struct {
	t    *testing.T
	conn *websocket.Conn
	ch   chan *pb.Frame // 关闭即代表连接已断
}

func dialAuthed(t *testing.T, token string) *wsClient {
	t.Helper()
	conn, _, err := websocket.DefaultDialer.Dial(wsURL, nil)
	if err != nil {
		t.Fatalf("ws dial: %v", err)
	}
	c := &wsClient{t: t, conn: conn}
	c.startReader()
	c.send(&pb.Frame{Body: &pb.Frame_AuthReq{AuthReq: &pb.AuthReq{
		Token: token, DeviceId: "smoke", Platform: "desktop",
	}}})
	f := c.read(5 * time.Second)
	if ar := f.GetAuthResp(); ar == nil || !ar.Ok {
		t.Fatalf("鉴权失败: %v", f.GetBody())
	}
	return c
}

func (c *wsClient) startReader() {
	ch := make(chan *pb.Frame, 256)
	c.ch = ch
	go func() {
		defer close(ch)
		for {
			_, data, err := c.conn.ReadMessage()
			if err != nil {
				return
			}
			f := &pb.Frame{}
			if proto.Unmarshal(data, f) == nil {
				ch <- f
			}
		}
	}()
}

func (c *wsClient) send(f *pb.Frame) {
	c.t.Helper()
	b, err := proto.Marshal(f)
	if err != nil {
		c.t.Fatal(err)
	}
	_ = c.conn.SetWriteDeadline(time.Now().Add(5 * time.Second))
	if err := c.conn.WriteMessage(websocket.BinaryMessage, b); err != nil {
		c.t.Fatalf("ws write: %v", err)
	}
}

// read 读一帧；超时或连接已断返回 nil
func (c *wsClient) read(timeout time.Duration) *pb.Frame {
	select {
	case f, ok := <-c.ch:
		if !ok {
			return nil
		}
		return f
	case <-time.After(timeout):
		return nil
	}
}

// closed 探测连接是否已被服务端主动断开；超时说明连接还在
func (c *wsClient) closed(timeout time.Duration) bool {
	select {
	case _, ok := <-c.ch:
		return !ok
	case <-time.After(timeout):
		return false
	}
}

// waitFor 等待某种帧出现（跳过心跳等无关帧）
func (c *wsClient) waitFor(timeout time.Duration, desc string, match func(*pb.Frame) bool) *pb.Frame {
	c.t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		f := c.read(time.Until(deadline))
		if f == nil {
			break
		}
		if match(f) {
			return f
		}
	}
	c.t.Fatalf("等待 %s 超时", desc)
	return nil
}

func TestSmoke(t *testing.T) {
	suffix := fmt.Sprintf("%d", time.Now().UnixNano()%1000000)

	// ---- 注册 / 登录 ----
	users := map[string]struct{ uid, token string }{}
	for _, name := range []string{"alice" + suffix, "bob" + suffix} {
		code, reg := postJSON(t, logicURL+"/v1/register", "", map[string]any{
			"username": name, "password": "secret123", "nickname": name,
		})
		if code >= 300 {
			t.Fatalf("注册 %s 失败: %d %v", name, code, reg)
		}
		code, login := postJSON(t, logicURL+"/v1/login", "", map[string]any{
			"username": name, "password": "secret123",
		})
		if code >= 300 {
			t.Fatalf("登录 %s 失败: %d %v", name, code, login)
		}
		token, _ := login["token"].(string)
		if token == "" {
			t.Fatalf("登录 %s 未返回 token: %v", name, login)
		}
		_, me := getJSON(t, logicURL+"/v1/me", token)
		uid, _ := me["uid"].(string)
		if uid == "" {
			t.Fatalf("/v1/me 未返回 uid: %v", me)
		}
		users[name] = struct{ uid, token string }{uid, token}
		t.Logf("注册并登录 %s uid=%s", name, uid)
	}
	alice := users["alice"+suffix]
	bob := users["bob"+suffix]

	// ---- 建连鉴权 ----
	aliceWS := dialAuthed(t, alice.token)
	defer aliceWS.conn.Close()
	bobWS := dialAuthed(t, bob.token)
	defer bobWS.conn.Close()

	// ---- 跨进程投递：logic 发起好友申请，必须经 Redis 总线推到 gateway 上的 bob ----
	code, resp := postJSON(t, logicURL+"/v1/contacts/request", alice.token, map[string]any{
		"to_uid": bob.uid, "message": "你好，我是 Alice",
	})
	if code >= 300 {
		t.Fatalf("发起好友申请失败: %d %v", code, resp)
	}
	ce := bobWS.waitFor(8*time.Second, "ContactEvent", func(f *pb.Frame) bool {
		return f.GetContactEvent() != nil
	})
	ev := ce.GetContactEvent()
	if ev.Type != "request" || ev.FromUid != alice.uid || ev.Message != "你好，我是 Alice" {
		t.Fatalf("ContactEvent 字段错位: %+v", ev)
	}
	t.Logf("✅ 跨进程投递（Redis pub/sub）正常: %+v", ev)

	// ---- 建会话 + 发消息 ----
	code, convResp := postJSON(t, logicURL+"/v1/conversations/single", alice.token, map[string]any{
		"peer_uid": bob.uid,
	})
	if code >= 300 {
		t.Fatalf("建会话失败: %d %v", code, convResp)
	}
	convID, _ := convResp["id"].(string)
	if convID == "" {
		t.Fatalf("会话 ID 为空: %v", convResp)
	}
	t.Logf("会话 %s", convID)

	aliceWS.send(&pb.Frame{Body: &pb.Frame_MsgSend{MsgSend: &pb.MsgSend{
		ClientMsgId: "smoke-cm-1", ConversationId: convID,
		MsgType: pb.MsgType_MSG_TEXT, Text: "第一条消息",
	}}})

	ack := aliceWS.waitFor(5*time.Second, "MsgAck", func(f *pb.Frame) bool {
		return f.GetMsgAck() != nil
	}).GetMsgAck()
	if ack.ClientMsgId != "smoke-cm-1" || ack.Seq == 0 {
		t.Fatalf("ACK 错位: %+v", ack)
	}
	t.Logf("✅ ACK: seq=%d server_msg_id=%s", ack.Seq, ack.ServerMsgId)

	// 接收方必须收到在线投递
	notify := bobWS.waitFor(5*time.Second, "MsgNotify", func(f *pb.Frame) bool {
		return f.GetMsgNotify() != nil
	}).GetMsgNotify()
	if notify.ConversationId != convID || notify.Seq != ack.Seq || notify.Text != "第一条消息" {
		t.Fatalf("MsgNotify 错位: %+v", notify)
	}
	t.Logf("✅ 在线投递正常 seq=%d", notify.Seq)

	// ---- seq 单调：连发 3 条 ----
	var seqs []uint64
	for i := 0; i < 3; i++ {
		aliceWS.send(&pb.Frame{Body: &pb.Frame_MsgSend{MsgSend: &pb.MsgSend{
			ClientMsgId: fmt.Sprintf("smoke-cm-%d", i+2), ConversationId: convID,
			MsgType: pb.MsgType_MSG_TEXT, Text: fmt.Sprintf("msg %d", i),
		}}})
		a := aliceWS.waitFor(5*time.Second, "MsgAck", func(f *pb.Frame) bool {
			return f.GetMsgAck() != nil && strings.HasPrefix(f.GetMsgAck().ClientMsgId, "smoke-cm-")
		}).GetMsgAck()
		if a.ClientMsgId != fmt.Sprintf("smoke-cm-%d", i+2) {
			t.Fatalf("ACK 串台: %+v", a)
		}
		seqs = append(seqs, a.Seq)
	}
	for i := 1; i < len(seqs); i++ {
		if seqs[i] != seqs[i-1]+1 {
			t.Fatalf("seq 不连续: %v", seqs)
		}
	}
	t.Logf("✅ seq 单调递增: %v", seqs)

	// ---- 幂等：重复 client_msg_id 不产生新消息 ----
	aliceWS.send(&pb.Frame{Body: &pb.Frame_MsgSend{MsgSend: &pb.MsgSend{
		ClientMsgId: "smoke-cm-1", ConversationId: convID,
		MsgType: pb.MsgType_MSG_TEXT, Text: "第一条消息",
	}}})
	dup := aliceWS.waitFor(5*time.Second, "重复 ACK", func(f *pb.Frame) bool {
		return f.GetMsgAck() != nil
	}).GetMsgAck()
	if dup.Seq != ack.Seq || dup.ServerMsgId != ack.ServerMsgId {
		t.Fatalf("幂等失败，重复发送产生了新 seq: 原=%+v 新=%+v", ack, dup)
	}
	t.Log("✅ client_msg_id 幂等生效")

	// ---- 拉补 ----
	bobWS.send(&pb.Frame{Body: &pb.Frame_MsgPullReq{MsgPullReq: &pb.MsgPullReq{
		ConversationId: convID, AfterSeq: 0, Limit: 200,
	}}})
	pull := bobWS.waitFor(5*time.Second, "MsgPullResp", func(f *pb.Frame) bool {
		return f.GetMsgPullResp() != nil
	}).GetMsgPullResp()
	if len(pull.Msgs) != 4 || pull.HasMore || pull.MaxSeq != 4 {
		t.Fatalf("拉补结果不对: n=%d hasMore=%v maxSeq=%d", len(pull.Msgs), pull.HasMore, pull.MaxSeq)
	}
	t.Logf("✅ 拉补 %d 条 maxSeq=%d", len(pull.Msgs), pull.MaxSeq)

	// ---- 历史翻页（before_seq 向上翻） ----
	code, hist := getJSON(t, fmt.Sprintf("%s/v1/conversations/%s/history?before_seq=3", logicURL, convID), alice.token)
	if code >= 300 {
		t.Fatalf("历史失败: %d %v", code, hist)
	}
	rawMsgs, _ := hist["msgs"].([]any)
	if len(rawMsgs) != 2 {
		t.Fatalf("before_seq=3 应返回 seq<3 的 2 条，实际 %d 条: %v", len(rawMsgs), hist)
	}
	for _, m := range rawMsgs {
		mm := m.(map[string]any)
		if seq, _ := mm["seq"].(float64); seq >= 3 {
			t.Fatalf("before_seq 语义错误，返回了 seq=%v", mm["seq"])
		}
	}
	t.Log("✅ history before_seq 向上翻页语义正确")

	// ---- 已读上报 ----
	aliceWS.send(&pb.Frame{Body: &pb.Frame_MsgRead{MsgRead: &pb.MsgRead{
		ConversationId: convID, UpToSeq: 4,
	}}})

	// ---- 慢连接保护 / 心跳：发心跳应回心跳 ----
	aliceWS.send(&pb.Frame{Body: &pb.Frame_Heartbeat{Heartbeat: &pb.Heartbeat{}}})
	hb := aliceWS.waitFor(5*time.Second, "Heartbeat", func(f *pb.Frame) bool {
		return f.GetHeartbeat() != nil
	})
	if hb.GetHeartbeat() == nil {
		t.Fatal("未收到心跳回包")
	}
	t.Log("✅ 心跳正常")

	// ---- admin：必须能起来（P0-4）----
	_, login := postJSON(t, adminURL+"/admin/login", "", map[string]any{"token": "smoke-admin-token"})
	if ok, _ := login["ok"].(bool); !ok {
		t.Fatalf("admin 登录失败: %v", login)
	}
	code, usersResp := getJSON(t, adminURL+"/admin/users?q="+alice.uid[:8], "smoke-admin-token")
	if code >= 300 {
		t.Fatalf("admin 用户列表失败: %d %v", code, usersResp)
	}
	t.Log("✅ admin 服务可用")

	// ---- 登录/注册按 IP 限流（默认 60 次/5 分钟/IP）----
	// loopback 不计数，这里用 X-Forwarded-For 伪装一个外部 IP（需 IM_TRUST_PROXY=true）
	xf := map[string]string{"X-Forwarded-For": "198.51.100.77"}
	limited := false
	for i := 0; i < 70; i++ {
		if c, _ := postJSONHdr(t, logicURL+"/v1/login", "", map[string]any{
			"username": alice.uid, "password": "wrong-password",
		}, xf); c == 429 {
			limited = true
			break
		}
	}
	if !limited {
		t.Fatal("连续 70 次失败登录仍未触发 IP 限流")
	}
	t.Log("✅ 登录 IP 限流生效（loopback 豁免，XFF 伪装 IP 正常计数）")

	// ---- 封禁即时踢下线（gateway 状态巡检）----
	_, dis := postJSON(t, adminURL+"/admin/users/"+bob.uid+"/disable", "smoke-admin-token", nil)
	if ok, _ := dis["ok"].(bool); !ok {
		t.Fatalf("封禁失败: %v", dis)
	}
	// 封禁必须同时覆盖 REST：早期只在 WS 首帧校验，被封用户拿旧 JWT 还能拉历史/加好友
	if c, _ := getJSON(t, logicURL+"/v1/me", bob.token); c != 403 {
		t.Fatalf("封禁后 REST 仍可访问: %d（应 403）", c)
	}
	t.Log("✅ 封禁对 REST 生效（403）")
	closed := false
	deadline := time.Now().Add(50 * time.Second)
	for time.Now().Before(deadline) {
		if bobWS.closed(10 * time.Second) {
			closed = true
			break
		}
	}
	if !closed {
		t.Fatal("封禁后 50s 内连接未被踢下线")
	}
	t.Log("✅ 封禁后连接被踢下线")

	// 重新启用，避免残留脏数据
	_, en := postJSON(t, adminURL+"/admin/users/"+bob.uid+"/enable", "smoke-admin-token", nil)
	t.Logf("重新启用: %v", en)
	if c, _ := getJSON(t, logicURL+"/v1/me", bob.token); c != 200 {
		t.Fatalf("重新启用后旧 token 应恢复可用: %d（应 200）", c)
	}
	t.Log("✅ 重新启用后旧 token 恢复可用")

	// ---- 令牌撤销：管理员重置密码 = 立即止损 ----
	// 语义是「账号可能已泄露」：所有已签发的 JWT 必须当场失效，而不是等 7 天自然过期
	newPass := "secret456"
	_, rp := postJSON(t, adminURL+"/admin/users/"+bob.uid+"/reset-password", "smoke-admin-token",
		map[string]any{"password": newPass})
	if ok, _ := rp["ok"].(bool); !ok {
		t.Fatalf("重置密码失败: %v", rp)
	}
	if c, _ := getJSON(t, logicURL+"/v1/me", bob.token); c != 401 {
		t.Fatalf("重置密码后旧 token 仍可用: %d（应 401）", c)
	}
	t.Log("✅ 重置密码后旧 token 立即失效（401）")

	// 新密码必须能正常登录，且拿到的 token 可用（别把正常用户也锁在门外）
	c, loginNew := postJSON(t, logicURL+"/v1/login", "", map[string]any{
		"username": "bob" + suffix, "password": newPass,
	})
	if c != 200 {
		t.Fatalf("重置后新密码登录失败: %d %v", c, loginNew)
	}
	newToken, _ := loginNew["token"].(string)
	if c, _ := getJSON(t, logicURL+"/v1/me", newToken); c != 200 {
		t.Fatalf("重置后新 token 不可用: %d", c)
	}
	t.Log("✅ 重置后新密码可登录、新 token 可用")
}
