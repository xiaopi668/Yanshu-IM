package siteconf

import (
	"crypto/tls"
	"net/smtp"
	"strings"
)

// smtpSendMail StartTLS / 明文 SMTP 发送（MVP；465 隐式 TLS 后续按需加）
func smtpSendMail(addr string, auth smtp.Auth, from string, to []string, msg []byte) error {
	host := addr
	if i := strings.LastIndex(addr, ":"); i > 0 {
		host = addr[:i]
	}
	conn, err := tls.Dial("tcp", addr, &tls.Config{ServerName: host, InsecureSkipVerify: false})
	useTLS := err == nil
	if useTLS {
		defer conn.Close()
		c, err := smtp.NewClient(conn, host)
		if err != nil {
			return err
		}
		defer c.Close()
		if auth != nil {
			if ok, _ := c.Extension("AUTH"); ok {
				if err = c.Auth(auth); err != nil {
					return err
				}
			}
		}
		if err = c.Mail(from); err != nil {
			return err
		}
		for _, rcpt := range to {
			if err = c.Rcpt(rcpt); err != nil {
				return err
			}
		}
		w, err := c.Data()
		if err != nil {
			return err
		}
		if _, err = w.Write(msg); err != nil {
			return err
		}
		return w.Close()
	}
	// 无 TLS：net/smtp（含 StartTLS 自动协商）
	return smtp.SendMail(addr, auth, from, to, msg)
}

func smtpAuth(user, pass, host string) smtp.Auth {
	if user == "" {
		return nil
	}
	return smtp.PlainAuth("", user, pass, host)
}
