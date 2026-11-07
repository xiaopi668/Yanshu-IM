// Package storage 可插拔对象存储：MinIO 或任意 S3 兼容端点（AWS S3 / R2 / OSS / B2 …）。
// 统一使用 minio-go 客户端，配置 region 与 path-style 即可对接不同实现。
package storage

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"log"
	"net/url"
	"regexp"
	"strings"
	"sync"
	"time"

	"github.com/minio/minio-go/v7"
	"github.com/minio/minio-go/v7/pkg/credentials"

	"im/internal/config"
)

// objectKeyRe 合法对象 key：PresignPut 生成 kind/YYYYMMDD/16位hex。
// 严格白名单，避免把任意字符串（含 ../ 或完整 URL）拼进对象存储路径。
var objectKeyRe = regexp.MustCompile(`^(image|file|audio|video)/[0-9]{8}/[0-9a-f]{16}$`)

// ValidObjectKey 判断 key 是否为本服务生成的对象 key。
// 逻辑层与消息层共用，保证「能写入的 key」与「能被引用的 key」是同一套规则。
func ValidObjectKey(key string) bool { return objectKeyRe.MatchString(key) }

// ObjectStore S3 兼容对象存储
type ObjectStore struct {
	client *minio.Client
	bucket string
}

func NewObjectStore(cfg *config.Config) (*ObjectStore, error) {
	client, err := minio.New(cfg.StorageEndpoint, &minio.Options{
		Creds:        credentials.NewStaticV4(cfg.StorageAccessKey, cfg.StorageSecretKey, ""),
		Secure:       cfg.StorageSecure,
		Region:       cfg.StorageRegion,
		BucketLookup: bucketLookup(cfg.StoragePathStyle),
	})
	if err != nil {
		return nil, err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := client.MakeBucket(ctx, cfg.StorageBucket, minio.MakeBucketOptions{Region: cfg.StorageRegion}); err != nil {
		exists, _ := client.BucketExists(ctx, cfg.StorageBucket)
		if !exists {
			return nil, err
		}
	}
	return &ObjectStore{client: client, bucket: cfg.StorageBucket}, nil
}

// Ref 线程安全的对象存储句柄。
// 对象存储可能比进程晚就绪（MinIO 冷启动、重启、临时故障），所以句柄允许先为空、
// 由后台重试填充：HTTP 服务不必为了等存储而阻塞启动，附件与归档也能在存储恢复后自动可用
// —— 而不是像早期实现那样"启动失败一次就永久禁用"。
type Ref struct {
	mu sync.RWMutex
	s  *ObjectStore
}

func (r *Ref) Get() *ObjectStore {
	r.mu.RLock()
	defer r.mu.RUnlock()
	return r.s
}

func (r *Ref) Set(s *ObjectStore) {
	r.mu.Lock()
	r.s = s
	r.mu.Unlock()
}

// ConnectWithRetry 后台重试直到对象存储可用（退避 2s→30s，不设上限）。
// stop 为 nil 表示永不主动退出。前 5 次失败逐条打日志，之后每 20 次打一次，避免刷屏。
func ConnectWithRetry(cfg *config.Config, ref *Ref, stop <-chan struct{}) {
	backoff := 2 * time.Second
	for attempt := 1; ; attempt++ {
		svc, err := NewObjectStore(cfg)
		if err == nil {
			ref.Set(svc)
			if attempt > 1 {
				log.Printf("[storage] 对象存储已就绪（第 %d 次尝试）", attempt)
			}
			return
		}
		if attempt <= 5 || attempt%20 == 0 {
			log.Printf("[storage] 对象存储尚不可用（第 %d 次尝试，%v 后重试）: %v", attempt, backoff, err)
		}
		select {
		case <-stop:
			return
		case <-time.After(backoff):
		}
		if backoff < 30*time.Second {
			backoff *= 2
			if backoff > 30*time.Second {
				backoff = 30 * time.Second
			}
		}
	}
}

func bucketLookup(pathStyle bool) minio.BucketLookupType {
	if pathStyle {
		return minio.BucketLookupPath
	}
	return minio.BucketLookupAuto
}

func randHex(n int) string {
	b := make([]byte, n)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

// PresignPut 预签名上传：返回对象 key 与 PUT URL
func (m *ObjectStore) PresignPut(kind string) (key string, putURL string, err error) {
	key = fmt.Sprintf("%s/%s/%s", kind, time.Now().Format("20060102"), randHex(8))
	u, err := m.client.PresignedPutObject(context.Background(), m.bucket, key, 15*time.Minute)
	if err != nil {
		return "", "", err
	}
	return key, u.String(), nil
}

// PresignGet 预签名下载
func (m *ObjectStore) PresignGet(key string, expire time.Duration) (string, error) {
	return m.PresignGetNamed(key, expire, "")
}

// PresignGetNamed 与 PresignGet 相同，但让对象存储下发时带上 Content-Disposition。
//
// 为什么必须这么做：对象 key 是随机串（有意为之，防止按名字推导出别人的附件），
// 直接 GET 只能拿到 key 当文件名 —— 中文名还会因为响应头被按 Latin-1 解释而变成乱码。
// 用 S3 的 response-content-disposition 参数把「原始文件名」签进 URL，
// 文件名就由我们自己决定，且不依赖对象自身的元数据。
//
// 头里同时给两份：filename（ASCII 兜底）+ filename*（RFC 5987，UTF-8）。
func (m *ObjectStore) PresignGetNamed(key string, expire time.Duration, filename string) (string, error) {
	if expire <= 0 {
		expire = 24 * time.Hour
	}
	params := url.Values{}
	if filename != "" {
		params.Set("response-content-disposition", ContentDisposition(filename))
	}
	u, err := m.client.PresignedGetObject(context.Background(), m.bucket, key, expire, params)
	if err != nil {
		return "", err
	}
	return u.String(), nil
}

// Upload 服务端直写对象（聊天记录归档等）
func (m *ObjectStore) Upload(key string, data []byte) error {
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	reader := bytes.NewReader(data)
	_, err := m.client.PutObject(ctx, m.bucket, key, reader, int64(len(data)),
		minio.PutObjectOptions{ContentType: "application/octet-stream"})
	return err
}

// ContentDisposition 生成同时兼容新旧客户端的下载头。
//
// RFC 5987 的 filename* 用百分号编码；filename 那份必须把非 ASCII 换成占位符，
// 否则部分浏览器会拿它覆盖 filename*，中文名照样乱码 —— 这是本 bug 的直接原因。
func ContentDisposition(filename string) string {
	name := strings.ReplaceAll(filename, "\"", "'")
	var ascii strings.Builder
	for _, r := range name {
		if r >= 0x20 && r < 0x7f {
			ascii.WriteRune(r)
		} else {
			ascii.WriteByte('_')
		}
	}
	fallback := ascii.String()
	if fallback == "" {
		fallback = "download"
	}
	return "attachment; filename=\"" + fallback + "\"; filename*=UTF-8''" + rfc5987Encode(name)
}

// rfc5987Encode 按 RFC 5987 的 attr-char 集合做百分号编码。
// 不能用 url.QueryEscape：它把空格编成 '+'，而 RFC 5987 里 '+' 是字面量。
func rfc5987Encode(s string) string {
	const safe = "!#$&+-.^_|~"
	var sb strings.Builder
	for i := 0; i < len(s); i++ {
		b := s[i]
		if (b >= 'a' && b <= 'z') || (b >= 'A' && b <= 'Z') || (b >= '0' && b <= '9') ||
			strings.IndexByte(safe, b) >= 0 {
			sb.WriteByte(b)
		} else {
			fmt.Fprintf(&sb, "%%%02X", b)
		}
	}
	return sb.String()
}
