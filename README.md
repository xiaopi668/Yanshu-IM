# 雁书 Yanshu — 自研跨平台即时通讯

支持 **Android / Windows / Linux / Web** 四端的私有化部署 IM。服务端 Go，客户端 Kotlin Multiplatform + Compose Multiplatform，音视频用自托管 LiveKit SFU。

## 功能

- **雁书号**：微信式自定义对外 ID（字母开头 5-20 位，注册可选自定义、默认自动生成、可改一次），登录支持用户名或雁书号
- **通讯录**：好友申请（附言）→ 同意/拒绝 → 好友列表、备注、删除；添加按雁书号精确搜索
- **朋友圈**：好友可见的动态流，文字 + 图片九宫格、点赞、评论
- **管理后台**（:10003）：用户搜索/封禁/重置密码、统计（在线数/消息量）、聊天记录归档下载；单页控制台，Token 登录
- **可插拔对象存储**：附件与聊天记录归档可指向任意 S3 兼容存储（MinIO / AWS S3 / R2 / OSS，配 region 与 path-style）
- 注册 / 登录（bcrypt + JWT）、多端在线
- 单聊、群聊（写扩散）、消息可靠投递（会话 seq + ACK + 离线拉补）
- 多媒体消息：图片 / 文件 / 语音 / 视频（预签名直传）
- 1v1 音视频通话（自托管 LiveKit SFU + 内嵌 TURN；Android / Web 端媒体接通，桌面端通话信令就绪）
- 会话列表、未读、消息历史拉取、离线增量同步
- 登录/注册按 IP 限流、管理后台封禁即时踢下线

### 聊天记录归档

服务端每日 03:00 自动把前一天的消息按会话导出为 JSONL 归档到对象存储（`archive/<日期>/<会话>.jsonl`）；管理后台可手动触发任意日期并查看归档清单。存储后端由 `IM_STORAGE_ENDPOINT/ACCESS_KEY/SECRET_KEY/BUCKET/REGION/PATH_STYLE` 配置，指向任意 S3 兼容存储。

## 架构

```
client/                    KMP 客户端
├── shared/                协议(手写 protobuf 编解码) + 连接层 + 本地缓存 + 通话/消息核心
│   ├── androidMain / desktopMain / wasmJsMain   平台 actual（网络栈、文件选择、打开链接）
└── composeApp/            一套 Compose UI 跑四端

server/                    Go 后端（零 RPC：三进程共享 MySQL + Redis）
├── cmd/gateway/           WebSocket 长连接网关（鉴权/心跳/出站队列/投递/通话信令状态机）
├── cmd/logic/             HTTP API（注册登录/好友/会话/历史/预签名/OIDC）
├── cmd/admin/             管理后台（用户封禁/统计/归档），Token 登录
└── internal/              auth(JWT/bcrypt) / messaging(seq+ACK+落库) / call / hub / storage / migrate

proto/im.proto             协议单一事实来源（Frame + CallSignal）
proto/golden/*.bin         双端协议一致性金样本（由 protoc 实现编码，Go/Kotlin 双向校验）

deploy/
├── docker-compose.yml     一键起全套
├── livekit.yaml           SFU 配置（内嵌 TURN: udp/3478 + 中继 49160-49200）
└── turn/turnserver.conf   可选的独立 coturn 配置（与 LiveKit 内嵌 TURN 二选一）
```

跨进程下行投递：三进程之间没有 RPC，gateway/logic 各自持有同一个 `hub`，通过
**Redis Pub/Sub（`im:push`）**广播下行帧 —— logic 上发起的事件（好友申请、群邀请、
admin 封禁踢线后的状态同步等）经总线送到 gateway 上的在线连接；发布方按实例 ID
跳过自己，避免本地重复投递。

消息可靠性模型（参考 OpenIM/WuKongIM）：每个会话单调递增 seq（Redis Lua 脚本，以
DB `MAX(seq)` 为下界自愈，重启/切实例不会回退），发送方收到 ACK 记录
server_msg_id/seq；接收方断线重连后按「本地 max seq → 服务端最新」增量拉补
（`MsgPullReq/Resp`），保证不丢不重。客户端重试用 `client_msg_id` 幂等去重。

## 快速开始（服务端）

```bash
# 1. 构建服务端镜像
cd server && docker build -t im-server:latest -f deploy/Dockerfile . && cd ..

# 2. 一键启动
docker compose -f deploy/docker-compose.yml up -d

# 服务端点
#   HTTP API   http://<host>:10002/v1/...
#   WebSocket  ws://<host>:10001/ws
#   LiveKit    ws://<host>:7880   MinIO: :9000
```

注册两个账号即可互通：

```bash
curl -X POST http://127.0.0.1:10002/v1/register \
  -d '{"username":"alice","password":"secret1"}'
```

## 客户端构建

```bash
cd client
./gradlew :composeApp:desktopRun                 # Windows/Linux 桌面
./gradlew :composeApp:wasmJsBrowserDevelopmentRun # Web（浏览器打开）
./gradlew :composeApp:assembleDebug              # Android APK（需要 Android SDK）
```

登录页填入服务端 API/WS 地址即可（默认 127.0.0.1:10002 / :10001）。

## 服务端配置

三个进程都读同一组环境变量（`deploy/docker-compose.yml` 里已给默认值）：

| 变量 | 默认 | 说明 |
|---|---|---|
| `IM_MYSQL_DSN` / `IM_REDIS_ADDR` / `IM_REDIS_PASS` | 本地开发值 | 数据层，三进程必须一致 |
| `IM_JWT_SECRET` / `IM_ADMIN_TOKEN` / `IM_LIVEKIT_API_SECRET` | 开发期默认值 | **生产必须覆盖**；`IM_REQUIRE_STRONG_SECRETS=true`（或 `IM_ENV=production`）时使用内置默认值会拒绝启动 |
| `IM_PUBLIC_BASE_URL` | 从请求推断 | OIDC `redirect_uri` 的对外基地址，**生产建议显式设置**，否则回调会指到内网地址 |
| `IM_ALLOWED_ORIGINS` | 不限 | 逗号分隔的 Origin 白名单；**不设置 = 保持原有宽松行为**（CORS `*`、WS 允许任意 Origin），设置后严格匹配 |
| `IM_TRUST_PROXY` | `false` | 置 `true` 才信任 `X-Forwarded-For` / `X-Forwarded-Host`（放在 Nginx/Caddy 后面时需要）；**行为变更**：默认不再信任 |
| `IM_AUTH_RATE_LIMIT` | `60` | 登录/注册每 IP、5 分钟内允许的次数；`0` 关闭。**loopback 不计数**（本机开发/单测不受影响）；若所有客户端都从同一 NAT 出口进来，请调大该值 |

OIDC 的 `redirect_uri` 默认必须等于 `{base}/v1/oidc/{name}/callback`；需要额外回跳地址时，
在管理后台「站点配置」的 OIDC provider 里配 `redirect_uris` 白名单。

站点相关的（好友申请开关、雁书号规则、OIDC、Turnstile、邮箱验证码）在管理后台
「站点配置」里改，落在数据库中，进程启动时读取。

## 测试

```bash
cd server && go test ./...                 # 服务端单测（含协议 golden 校验）
cd server && go vet ./...

# 跨进程链路冒烟：需先起 MySQL/Redis 并运行 logic/gateway/admin
# （compose 里已带 MySQL/Redis，起完后三个进程的环境变量见 docker-compose.yml）
go test -tags smoke ./cmd/gateway -run TestSmoke -v

cd client && gradle :shared:desktopTest    # KMP 客户端单测 + E2E

# 改了 proto 之后重建双端金样本
./proto/gen.sh --update
cd client && UPDATE_CLIENT_GOLDEN=1 gradle :shared:desktopTest --tests 'im.client.proto.ProtoGoldenTest'
```

客户端 E2E（`ImClientE2eTest` / `Phase2E2eTest` / `Phase3E2eTest`）需要本地起好
MySQL/Redis + logic + gateway，并预先注册 `alice` / `bob`（密码 `secret1`）：

```bash
curl -X POST http://127.0.0.1:10002/v1/register -d '{"username":"alice","password":"secret1"}'
curl -X POST http://127.0.0.1:10002/v1/register -d '{"username":"bob","password":"secret1"}'
```

## 已知限制

- 通话会话状态（`internal/call`）与管理后台在线数统计仍是**单进程内存**状态，
  多副本部署时邀请/统计只在本副本准确；消息投递与 seq 已经是跨进程正确的
- 朋友圈动态按 `moment.id`（雪花，字典序）分页，2031 年前等价于时间序
- 只有客户端 → 服务端的已读上报（`MsgRead`），服务端没有「已读回执」下行帧
- TLS 需要在反向代理层终止，服务端本身只监听明文 HTTP/WS
- 桌面端音视频媒体（libwebrtc JNI 绑定）、Web 端通话媒体（livekit-client JS interop）
- Android 系统推送（自建 ntfy/FCM 网关）、E2E 加密
- 客户端 `pickFile()` / `openUrl()` 的 Android 实现待定（需要平台 API 决策）

## 许可证

本项目基于 [AGPL-3.0](LICENSE) 开源：修改后的服务端若对网络提供服务，须以同许可证开源。商业闭源集成需另行授权。
