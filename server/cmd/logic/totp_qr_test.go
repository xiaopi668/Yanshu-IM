package main

import (
	"strings"
	"testing"
)

// qrRows 的结构校验。
//
// 真正的"能不能扫"由外部解码器验证过（用 zbarimg 解回 PNG，内容与 otpauth 地址完全一致）；
// 这里只钉住不依赖解码器的形状契约：方阵、静区、定位图案位置。
func TestQrRowsShape(t *testing.T) {
	rows, err := qrRows("otpauth://totp/Yanshu:alice?secret=JBSWY3DPEHPK3PXP&issuer=Yanshu")
	if err != nil {
		t.Fatalf("生成失败: %v", err)
	}
	n := len(rows)
	if n < 21 {
		t.Fatalf("尺寸异常: %d", n)
	}
	// 尺寸必须是 17 + 4v，再加上两侧各 4 模块静区
	if (n-8-17)%4 != 0 {
		t.Fatalf("尺寸不符合 17+4v(+8 静区): %d", n)
	}
	for i, line := range rows {
		if len(line) != n {
			t.Fatalf("第 %d 行长度 %d，应为 %d", i, len(line), n)
		}
		if strings.Trim(line, "01") != "" {
			t.Fatalf("第 %d 行含非 0/1 字符", i)
		}
	}
	// 静区：最外 4 圈必须全浅色（扫码器靠它定位）
	for i := 0; i < 4; i++ {
		if strings.ContainsRune(rows[i], '1') {
			t.Fatalf("顶部静区第 %d 行有深色模块", i)
		}
		if strings.ContainsRune(rows[n-1-i], '1') {
			t.Fatalf("底部静区第 %d 行有深色模块", i)
		}
		if rows[i][0] == '1' || rows[i][n-1] == '1' {
			t.Fatalf("左右静区第 %d 行有深色模块", i)
		}
	}
	// 定位图案：左上角（静区之后）7x7 的转角与中心必须是深色
	const q = 4
	if rows[q][q] != '1' || rows[q][q+6] != '1' || rows[q+6][q] != '1' {
		t.Fatal("左上定位图案外框不对")
	}
	if rows[q+3][q+3] != '1' {
		t.Fatal("左上定位图案中心应为深色")
	}
	if rows[q+1][q+1] != '0' {
		t.Fatal("定位图案内圈应为浅色")
	}
}
