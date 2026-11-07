package storage

import (
	"strings"
	"testing"
)

// 文件名编码是这个 bug 的根因所在：中文名直接塞进 Content-Disposition 会被按
// Latin-1 解释成乱码。这里钉住三条：ASCII 兜底、RFC 5987 编码、引号处理。
func TestContentDisposition(t *testing.T) {
	cd := ContentDisposition("会议纪要.pdf")
	if !strings.HasPrefix(cd, "attachment; ") {
		t.Fatalf("应是 attachment: %s", cd)
	}
	// filename 那份必须纯 ASCII（非 ASCII 换成下划线），否则部分浏览器会用它覆盖 filename*
	if strings.Contains(cd, "会议纪要") {
		t.Fatalf("filename 兜底不应包含原始中文: %s", cd)
	}
	if !strings.Contains(cd, "filename*=UTF-8''") {
		t.Fatalf("缺少 RFC 5987 的 filename*: %s", cd)
	}
	// UTF-8 百分号编码：会=%E4%BC%9A 议=%E8%AE%AE
	if !strings.Contains(cd, "%E4%BC%9A%E8%AE%AE") {
		t.Fatalf("filename* 编码不对: %s", cd)
	}
	// 空格必须是 %20，不能是 '+'（QueryEscape 的坑）
	cd2 := ContentDisposition("my report 2026.pdf")
	if strings.Contains(cd2, "filename*=UTF-8''my+report") {
		t.Fatalf("空格被编成了 '+': %s", cd2)
	}
	if !strings.Contains(cd2, "my%20report%202026.pdf") {
		t.Fatalf("空格应编码为百分号-20: %s", cd2)
	}
	// 引号要换掉，否则会截断头
	cd3 := ContentDisposition("a\"b.txt")
	if strings.Count(cd3, "\"") != 2 {
		t.Fatalf("引号未被替换: %s", cd3)
	}
	// 全中文时兜底不能是空的
	cd4 := ContentDisposition("图片.png")
	if strings.Contains(cd4, "filename=\"\"") {
		t.Fatalf("ASCII 兜底不应为空: %s", cd4)
	}
}
