// Package storage 可插拔对象存储：MinIO 或任意 S3 兼容端点（AWS S3 / R2 / OSS / B2 …）。
// 统一使用 minio-go 客户端，配置 region 与 path-style 即可对接不同实现。
package storage

import (
	"bytes"
	"context"
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"net/url"
	"time"

	"github.com/minio/minio-go/v7"
	"github.com/minio/minio-go/v7/pkg/credentials"

	"im/internal/config"
)

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
	if expire <= 0 {
		expire = 24 * time.Hour
	}
	u, err := m.client.PresignedGetObject(context.Background(), m.bucket, key, expire, url.Values{})
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
