package storage

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"net/url"
	"time"

	"github.com/minio/minio-go/v7"
	"github.com/minio/minio-go/v7/pkg/credentials"
)

// MinIO 对象存储封装：预签名直传/下载
type Minio struct {
	client *minio.Client
	bucket string
	secure bool
}

func NewMinio(endpoint, accessKey, secretKey, bucket string, secure bool) (*Minio, error) {
	client, err := minio.New(endpoint, &minio.Options{
		Creds:  credentials.NewStaticV4(accessKey, secretKey, ""),
		Secure: secure,
	})
	if err != nil {
		return nil, err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := client.MakeBucket(ctx, bucket, minio.MakeBucketOptions{}); err != nil {
		exists, _ := client.BucketExists(ctx, bucket)
		if !exists {
			return nil, err
		}
	}
	return &Minio{client: client, bucket: bucket, secure: secure}, nil
}

func randToken() string {
	b := make([]byte, 16)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

// PresignPut 预签名上传：返回 PUT URL 与对象 key
func (m *Minio) PresignPut(kind string) (key string, putURL string, err error) {
	key = fmtKey(kind)
	u, err := m.client.PresignedPutObject(context.Background(), m.bucket, key, 15*time.Minute)
	if err != nil {
		return "", "", err
	}
	return key, u.String(), nil
}

// PresignGet 预签名下载
func (m *Minio) PresignGet(key string, expire time.Duration) (string, error) {
	if expire <= 0 {
		expire = 24 * time.Hour
	}
	u, err := m.client.PresignedGetObject(context.Background(), m.bucket, key, expire, url.Values{})
	if err != nil {
		return "", err
	}
	return u.String(), nil
}

func fmtKey(kind string) string {
	return kind + "/" + time.Now().Format("20060102") + "/" + randToken()
}
