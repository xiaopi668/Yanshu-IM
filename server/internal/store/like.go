package store

import "strings"

// LikePattern 转义 SQL LIKE 模式的通配符（%、_、\），
// 让用户输入按字面匹配，避免 "%%" 之类输入把查询放大成全表扫描。
// MySQL 的 LIKE 默认以反斜杠作为转义符，因此这里按反斜杠规则转义。
func LikePattern(s string) string {
	return strings.NewReplacer(`\`, `\\`, `%`, `\%`, `_`, `\_`).Replace(s)
}

// LikeQuery 生成带通配符包裹的 LIKE 模式
func LikeQuery(s string) string {
	return "%" + LikePattern(s) + "%"
}
