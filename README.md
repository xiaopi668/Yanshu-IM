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
- 1v1 音视频通话（自托管 LiveKit SFU + 内嵌 TURN）：**服务端**信令状态机与 LiveKit token 签发已完成；
  **客户端媒体层尚未接入**，接听后只会停在通话状态、没有画面与声音（见「已知限制」）
- 会话列表、未读、消息历史拉取、离线增量同步
- 登录/注册按 IP 限流；管理后台封禁对 **WS 与 REST 同时生效**，重置密码会递增令牌版本，
  使该账号已签发的 JWT **立即失效**（不是等 7 天自然过期）

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

**失败必须可见（不允许静默丢消息）**：协议 `Frame` 里有 `Error`（字段 12）。
服务端对任何 C→S 请求失败（不是会话成员、落库/Redis 失败、不支持的帧类型）都会回一帧
带 `client_msg_id` 的失败应答，绝不只写服务端日志；客户端收到后立刻把该消息标成
「发送失败，点击重试」。客户端侧还有一道兜底：发送后 10 秒未收到 ACK 自动标失败，
重连成功后按 `client_msg_id` 自动补发所有未确认消息（服务端幂等去重，不会产生重复消息）。

**账号状态与令牌撤销**：每个 HTTP 请求都会校验 `user_state`（是否封禁 + 令牌版本），
不止登录和 WS 首帧 —— 否则封禁对 REST 形同虚设。校验结果缓存在 Redis（`im:ustate:<uid>`），
管理后台封禁/启用/重置密码时**主动失效缓存**，因此是立即生效的；WS 连接由 gateway 每 10s
巡检一次，发现封禁或令牌版本落后就断开。重置密码会 `token_version+1`，
该账号此前签发的所有 JWT 当场作废。

**附件下载（不让账号凭证随消息扩散）**：消息里只存对象存储 key（`Attachment.url` 承载 key），
取用时调用方用**自己的**登录态向 `POST /v1/attachments/ticket` 换一张短时票据
（HMAC 签名、与单个 key 绑定、10 分钟过期），再 `GET /v1/download?key=..&ticket=..`。
授权判定覆盖三种引用来源：会话消息（须是该会话成员）、用户头像（站内可见）、朋友圈图片（作者本人或好友）。
`/v1/download` 本身不校验登录态 —— 票据就是这次下载的凭证，因为"下载"按钮会在系统浏览器里
`openUrl`、带不上 `Authorization` 头；但 key 必须命中生成端白名单，归档对象（`archive/..`）
不在其中，因此"按日期 + 会话 ID 推导 key 拖走聊天归档"的路径是关闭的。

## 快速开始（服务端）

```bash
# 1. 准备密钥：compose 里所有密钥都是 ${VAR:?} 必填，缺哪个会直接报错并写明变量名
cp deploy/.env.example deploy/.env
# 按文件内提示生成随机值，例如：
#   openssl rand -hex 32  → IM_JWT_SECRET / IM_ADMIN_TOKEN / IM_LIVEKIT_API_SECRET
#   openssl rand -hex 16  → IM_MYSQL_PASSWORD / IM_MINIO_ROOT_PASSWORD

# 2. 构建服务端镜像
cd server && docker build -t im-server:latest -f deploy/Dockerfile . && cd ..

# 3. 启动
docker compose -f deploy/docker-compose.yml up -d

# 服务端点
#   HTTP API   http://<host>:10002/v1/...     对外
#   WebSocket  ws://<host>:10001/ws           对外
#   LiveKit    ws://<host>:7880               对外
#   管理后台   http://127.0.0.1:10003/admin   只绑本机，请走 SSH 隧道或反代加 TLS
#   MinIO      http://127.0.0.1:9000          只绑本机（含全部附件与聊天归档）
```

> 为什么强制填密钥：早期版本给所有密钥留了"开发期默认值"，照着一键启动的生产环境等于
> JWT 密钥 / 管理令牌 / MinIO 口令全是公开已知值 —— 任何人都能伪造登录、接管后台与对象存储。

注册两个账号即可互通：

```bash
curl -X POST http://127.0.0.1:10002/v1/register \
  -d '{"username":"alice","password":"secret1"}'
```

## 客户端构建

> ⚠️ 仓库**尚未提交 Gradle Wrapper**（缺 `gradlew` 与 `gradle/wrapper/gradle-wrapper.jar`），
> 所以全新 clone 里下面这些 `./gradlew` 会直接报"没有那个文件"。先用本机 Gradle 生成一次：
>
> ```bash
> cd client && gradle wrapper --gradle-version 9.5.0   # 需要本机已装 Gradle
> ```

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
| `IM_MYSQL_DSN` / `IM_REDIS_ADDR` / `IM_REDIS_PASS` | 本地开发值 | 数据层，三进程必须一致；容器里由 `entrypoint.sh` 用 `IM_MYSQL_PASSWORD` 拼装 |
| `IM_JWT_SECRET` | 无（必填） | 登录令牌签名密钥。缺失或仍是 `dev-secret-change-me` 时**拒绝启动**；泄露 = 任何人可伪造任意用户登录 |
| `IM_ADMIN_TOKEN` | 无（必填） | 管理后台令牌，仅 `admin` 角色校验 |
| `IM_LIVEKIT_API_SECRET` | 无（必填） | 音视频密钥，仅 `gateway` 角色校验（真正签发 token 的是它） |
| `IM_ENV` | 空 | 置 `production` 等同 `IM_REQUIRE_STRONG_SECRETS=true`：使用内置默认密钥直接拒绝启动。**校验按 `IM_ROLE` 过滤**，只检查本角色真正用到的密钥 |
| `IM_NODE_ID` | 角色默认（gateway=1 / logic=2） | 雪花 ID 节点号。**多副本部署必须给每个实例设不同值**，否则同毫秒内会生成相同 ID（消息撞 `uk_msg_id`、注册撞主键、建群撞群 ID） |
| `IM_PUBLIC_BASE_URL` | 从请求推断 | OIDC `redirect_uri` 与附件下载票据的对外基地址，**生产必须显式设置**，否则会指到内网地址 |
| `IM_ALLOWED_ORIGINS` | 不限 | 逗号分隔的 Origin 白名单；**不设置 = 保持原有宽松行为**（CORS `*`、WS 允许任意 Origin），设置后严格匹配 |
| `IM_TRUST_PROXY` | `false` | 置 `true` 才信任 `X-Forwarded-For` / `X-Forwarded-Host`（放在 Nginx/Caddy 后面时需要）。**只支持一层可信反代**：取 XFF 最后一段；且来自代理头的地址不再享受 loopback 限流豁免 |
| `IM_AUTH_RATE_LIMIT` | `60` | 登录/注册每 IP、5 分钟内允许的次数；`0` 关闭。**仅直连的 loopback 不计数**（本机开发/单测不受影响）；若所有客户端都从同一 NAT 出口进来，请调大该值 |

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
- **多副本部署必须给每个实例设置不同的 `IM_NODE_ID`**，否则雪花 ID 会碰撞（默认 gateway=1 / logic=2）
- **客户端没有任何 WebRTC/媒体层实现**：`CallController.onSessionReady` 没有平台实现，
  接听后进入的是"假 InCall"状态，必须按平台补齐（Android webrtc-android / Web livekit-client JS interop）
  或先显式禁用通话入口
- 通话信令（`CallSignal`）自身没有失败应答，被叫忙/拒接等语义仍靠 `CALL_BUSY`/`CALL_REJECT` 表达
- 朋友圈动态按 `moment.id`（雪花，字典序）分页，2031 年前等价于时间序
- 只有客户端 → 服务端的已读上报（`MsgRead`），服务端没有「已读回执」下行帧
- TLS 需要在反向代理层终止，服务端本身只监听明文 HTTP/WS
- 桌面端音视频媒体（libwebrtc JNI 绑定）、Web 端通话媒体（livekit-client JS interop）
- Android 系统推送（自建 ntfy/FCM 网关）、E2E 加密
- Android 端 `pickFile()` 仍是空实现（恒返回 null），**Android 无法发图片/文件**；
  `openUrl()` 已实现。Android/桌面端的 Turnstile 也是空实现，站点一旦开启 Turnstile 这两端将无法注册登录
- 仓库未提交 Gradle Wrapper（缺 `gradlew`），客户端构建需先用本机 Gradle 生成一次
- 归档 key 已改为随机串，管理后台通过 `archive_log.object_key` 查看；旧版本用
  `archive/<日期>/<会话ID>.jsonl` 落下的历史对象不在新白名单内，需要人工迁移或清理

## 许可证

本项目基于 [AGPL-3.0](LICENSE) 开源：修改后的服务端若对网络提供服务，须以同许可证开源。商业闭源集成需另行授权。
