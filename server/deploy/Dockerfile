FROM golang:1.27-alpine AS build
WORKDIR /src
# 构建版本：docker build --build-arg VERSION=$(git rev-parse --short HEAD)
# 启动时会打印，/healthz 也会返回，方便确认线上跑的是哪个提交
ARG VERSION=dev
COPY go.mod go.sum ./
RUN go mod download
COPY . .
# -trimpath 去掉构建机路径；-s -w 去掉符号表（镜像更小，也少暴露内部结构）
RUN CGO_ENABLED=0 go build -trimpath -ldflags "-s -w -X main.version=${VERSION}" -o /out/im-logic ./cmd/logic && \
    CGO_ENABLED=0 go build -trimpath -ldflags "-s -w -X main.version=${VERSION}" -o /out/im-gateway ./cmd/gateway && \
    CGO_ENABLED=0 go build -trimpath -ldflags "-s -w -X main.version=${VERSION}" -o /out/im-admin ./cmd/admin

FROM alpine:3.20
RUN apk add --no-cache ca-certificates mysql-client && \
    addgroup -S im && adduser -S -G im im
COPY --from=build /out/im-logic /usr/local/bin/im-logic
COPY --from=build /out/im-gateway /usr/local/bin/im-gateway
COPY --from=build /out/im-admin /usr/local/bin/im-admin
COPY deploy/schema.sql /usr/local/share/im/schema.sql
COPY deploy/entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh
# 非 root 运行：进程只监听 >1024 的端口、不写本地磁盘，不需要任何特权
USER im
# 健康检查放在 compose 里按角色指定端口（logic 10002 / gateway 10001 / admin 10003），
# 探针本身由各进程的 /healthz 提供（会 ping MySQL 与 Redis）。
ENTRYPOINT ["/entrypoint.sh"]
