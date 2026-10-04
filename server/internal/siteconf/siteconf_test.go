package siteconf

import (
	"net/smtp"
	"strings"
	"testing"
)

// fakeMail 替换真实 SMTP 客户端，用来断言「发出去的到底是什么」。
type fakeMail struct {
	addr string
	from string
	to   []string
	msg  string
	err  error
}

func (f *fakeMail) Send(addr string, _ smtp.Auth, from string, to []string, msg []byte) error {
	f.addr, f.from, f.to, f.msg = addr, from, to, string(msg)
	return f.err
}

func withFakeMail(t *testing.T) *fakeMail {
	t.Helper()
	f := &fakeMail{}
	old := mailClient
	mailClient = f
	t.Cleanup(func() { mailClient = old })
	return f
}

func TestSendTestMailUsesConfiguredSMTP(t *testing.T) {
	f := withFakeMail(t)
	c := Conf{SMTPHost: "smtp.example.com", SMTPPort: 587, SMTPUser: "bot@example.com", SMTPPass: "pw"}

	if err := SendTestMail(c, "admin@example.com"); err != nil {
		t.Fatalf("应发送成功: %v", err)
	}
	if f.addr != "smtp.example.com:587" {
		t.Errorf("地址应为 host:port，实际 %q", f.addr)
	}
	if len(f.to) != 1 || f.to[0] != "admin@example.com" {
		t.Errorf("收件人不对: %v", f.to)
	}
	// 没配 SMTPFrom 时用 SMTPUser 当发件人（很多服务商要求 From 与登录账号一致）
	if f.from != "bot@example.com" {
		t.Errorf("发件人应回落到 SMTPUser，实际 %q", f.from)
	}
	for _, want := range []string{"To: admin@example.com", "Subject: 雁书 SMTP 测试邮件", "smtp.example.com:587"} {
		if !strings.Contains(f.msg, want) {
			t.Errorf("邮件内容缺少 %q：\n%s", want, f.msg)
		}
	}
}

func TestSendTestMailPrefersSMTPFrom(t *testing.T) {
	f := withFakeMail(t)
	c := Conf{SMTPHost: "h", SMTPPort: 25, SMTPUser: "u", SMTPFrom: "noreply@example.com"}
	if err := SendTestMail(c, "a@b.com"); err != nil {
		t.Fatalf("应发送成功: %v", err)
	}
	if f.from != "noreply@example.com" {
		t.Errorf("配了 SMTPFrom 就该用它，实际 %q", f.from)
	}
}

// 配置类错误必须在发信前就拦住并给出可读提示（管理后台直接把这句话显示给管理员）
func TestSendTestMailRejectsBadConfig(t *testing.T) {
	withFakeMail(t)
	cases := []struct {
		name string
		conf Conf
		to   string
		want string
	}{
		{"没有 SMTPHost", Conf{}, "a@b.com", "SMTP 未配置"},
		{"收件地址非法", Conf{SMTPHost: "h", SMTPPort: 25}, "not-an-email", "格式不正确"},
		{"收件地址为空", Conf{SMTPHost: "h", SMTPPort: 25}, "  ", "格式不正确"},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			err := SendTestMail(c.conf, c.to)
			if err == nil {
				t.Fatal("应当报错")
			}
			if !strings.Contains(err.Error(), c.want) {
				t.Errorf("错误信息应包含 %q，实际 %q", c.want, err.Error())
			}
		})
	}
}
