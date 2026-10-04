// Package metrics 极简 Prometheus 文本格式指标导出。
//
// 为什么不引 prometheus/client_golang：三个进程都只需要少量计数器与 gauge，
// 手写文本导出（百余行）比给每个二进制都背上一个重量级依赖更划算，也更好审计。
// 指标名统一 im_ 前缀；标签集合固定（role / code / kind），不会出现基数爆炸。
package metrics

import (
	"fmt"
	"io"
	"net/http"
	"runtime"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

var (
	mu       sync.RWMutex
	counters = map[string]float64{}
	gauges   = map[string]func() float64{}
	started  = time.Now()
)

// key 把指标名与标签拼成 Prometheus 的 name{k="v"} 形式（kv 为交替的键值对）
func key(name string, kv []string) string {
	if len(kv) == 0 {
		return name
	}
	var b strings.Builder
	b.WriteString(name)
	b.WriteByte('{')
	for i := 0; i+1 < len(kv); i += 2 {
		if i > 0 {
			b.WriteByte(',')
		}
		fmt.Fprintf(&b, "%s=%q", kv[i], kv[i+1])
	}
	b.WriteByte('}')
	return b.String()
}

// Inc 计数 +1
func Inc(name string, kv ...string) { Add(name, 1, kv...) }

// Add 计数累加（delta 可为负，但计数器语义上应单调）
func Add(name string, delta float64, kv ...string) {
	k := key(name, kv)
	mu.Lock()
	counters[k] += delta
	mu.Unlock()
}

// Gauge 注册「采集时求值」的瞬时指标，例如在线连接数。
// 传函数而不是定期回填：既没有后台 goroutine，也不会因为采样间隔错过峰值。
func Gauge(name string, f func() float64, kv ...string) {
	k := key(name, kv)
	mu.Lock()
	gauges[k] = f
	mu.Unlock()
}

// Handler 输出 Prometheus 文本格式
func Handler() http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
		_, _ = io.WriteString(w, Render())
	}
}

// Render 生成指标文本（测试与 /metrics 共用同一份实现）
func Render() string {
	var b strings.Builder
	fmt.Fprintf(&b, "im_uptime_seconds %g\n", time.Since(started).Seconds())
	fmt.Fprintf(&b, "im_goroutines %d\n", runtime.NumGoroutine())
	var m runtime.MemStats
	runtime.ReadMemStats(&m)
	fmt.Fprintf(&b, "im_memory_alloc_bytes %d\n", m.Alloc)
	fmt.Fprintf(&b, "im_memory_sys_bytes %d\n", m.Sys)

	// 先在锁内把名字与取值都取出来，再在锁外渲染：
	// gauge 函数可能去拿别的锁（如 hub 的读写锁），持锁调用容易埋死锁。
	mu.RLock()
	type sample struct {
		name string
		val  float64
	}
	out := make([]sample, 0, len(counters)+len(gauges))
	for k, v := range counters {
		out = append(out, sample{k, v})
	}
	pend := make([]struct {
		name string
		f    func() float64
	}, 0, len(gauges))
	for k, f := range gauges {
		pend = append(pend, struct {
			name string
			f    func() float64
		}{k, f})
	}
	mu.RUnlock()

	for _, p := range pend {
		out = append(out, sample{p.name, p.f()})
	}
	sort.Slice(out, func(i, j int) bool { return out[i].name < out[j].name })
	for _, s := range out {
		fmt.Fprintf(&b, "%s %g\n", s.name, s.val)
	}
	return b.String()
}

// statusRecorder 记录响应状态码，供请求计数使用
type statusRecorder struct {
	http.ResponseWriter
	code int
}

func (s *statusRecorder) WriteHeader(code int) {
	if s.code == 0 {
		s.code = code
	}
	s.ResponseWriter.WriteHeader(code)
}

func (s *statusRecorder) Write(b []byte) (int, error) {
	if s.code == 0 {
		s.code = 200
	}
	return s.ResponseWriter.Write(b)
}

// Instrument 统计 HTTP 请求数与状态码分布。
//
// 注意：包装 ResponseWriter 会让 http.Hijacker 失效，因此**不要**用在
// gateway 的 WebSocket 路由上（upgrade 需要 Hijack）。logic / admin 无此需求。
func Instrument(role string, next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		rec := &statusRecorder{ResponseWriter: w}
		next.ServeHTTP(rec, r)
		if rec.code == 0 {
			rec.code = 200
		}
		Inc("im_http_requests_total", "role", role, "code", strconv.Itoa(rec.code))
	})
}
