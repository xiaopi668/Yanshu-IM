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

**可观测性**：三个进程都提供 `/healthz`（会 ping MySQL 与 Redis，异常返回 503）与
`/metrics`（Prometheus 文本格式）。指标包括：在线连接数 `im_gateway_online_conns`、
建连数 `im_ws_connections_total`、鉴权失败 `im_ws_auth_failures_total{reason}`、
发送/拉取失败 `im_msg_send_errors_total{code}`、出站队列打满 `im_gateway_send_queue_full_total`、
跨进程广播失败 `im_pubsub_publish_errors_total`、HTTP 请求分布 `im_http_requests_total{role,code}`，
以及 `im_uptime_seconds` / `im_goroutines` / `im_memory_*`。
compose 里三个服务都配了基于 `/healthz` 的 healthcheck —— `docker ps` 能直接看出实例是否健康。

**构建版本可追溯**：镜像构建时 `--build-arg VERSION=$(git rev-parse --short HEAD)` 注入，
进程启动会打印 `version=...`，`/healthz` 也会返回它。

**账号状态与令牌撤销**：每个 HTTP 请求都会校验 `user_state`（是否封禁 + 令牌版本），
不止登录和 WS 首帧 —— 否则封禁对 REST 形同虚设。校验结果缓存在 Redis（`im:ustate:<uid>`），
管理后台封禁/启用/重置密码时**主动失效缓存**，因此是立即生效的；WS 连接由 gateway 每 10s
巡检一次，发现封禁或令牌版本落后就断开。重置密码会 `token_version+1`，
该账号此前签发的所有 JWT 当场作废。

**登录 / 注册是两个独立界面**：登录用「用户名 / 雁书号 / **邮箱** + 密码」，注册用「用户名 + 密码 + 邮箱 + 验证码」。
两者用卡片底部的链接互相切换；站点关闭注册时不提供「去注册」，但仍能退回登录。

**用邮箱登录**：服务端按 用户名 → 雁书号 → 邮箱 的顺序解析登录标识（三者都可直接填在同一个输入框）。
注意 `user.email` **没有唯一索引**，同一邮箱可能被多个账号绑定；这种情况下登录会返回 `409` 并提示
「该邮箱绑定了多个账号，请改用用户名或雁书号登录」，而**不会随便挑一个**（那会造成「用我的邮箱登进别人账号」）。
邮箱不存在与密码错误都返回同一句 `bad credentials`，避免变成账号枚举接口。

**OIDC 登录（默认不需要用户复制令牌）**：授权成功后令牌怎么回到客户端，按平台分三种，都不用用户手工复制：

- **Web**：页面本身就是回调目标（同源），服务端把令牌放在 URL fragment，页面加载时读取并**立即从地址栏抹掉**
- **桌面**：客户端起本地回环监听器（`127.0.0.1` 随机端口，RFC 8252 做法），服务端以 query 回跳给它，浏览器显示「登录成功，可以关闭此页」
- **Android**：自定义 scheme `yanshu://oidc/callback` 把令牌交回 App（`singleTask` + `onNewIntent`）

桌面与 Android 的回跳目标不在本站同源范围内，需要在 `IM_OIDC_RETURN_ALLOWLIST` 里显式放行；
**没配置时会自动退回「浏览器展示令牌 + 用户粘贴」的老流程**（登录页出现令牌输入框），不会把人锁在门外。

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

```bash
cd client
./gradlew :composeApp:desktopRun                 # Windows/Linux 桌面
./gradlew :composeApp:wasmJsBrowserDevelopmentRun # Web（浏览器打开）
./gradlew :composeApp:assembleDebug              # Android APK（需要 Android SDK）
```

**Android 构建的前置条件**（不满足时 Android 目标会被自动跳过，只构建 desktop/wasm —— 这是有意的，方便只做桌面/Web 开发时不必装 SDK）：

| 项 | 要求 |
|---|---|
| JDK | 17+ |
| `ANDROID_HOME` | 指向 Android SDK（未设置时跳过 Android 目标） |
| SDK 组件 | `platforms;android-37.0` + `build-tools;37.0.0`（用 `sdkmanager --install` 安装） |
| Gradle / AGP | Gradle 9.6+、AGP 9.4.x（wrapper 已配置，无需手动指定） |

CI 的 `android` job 会自动装好这些，并把 APK 作为 artifact 上传。

### 构建期配置：默认服务器地址 / 隐藏地址输入

私有化部署时可以把服务端地址**固化进客户端**，登录页不再暴露「服务器地址」入口：

```bash
cd client
# 打包时指定（三个平台通用，含 Web）
./gradlew :composeApp:assembleDebug \
  -Pim.api=https://im.example.com \
  -Pim.ws=wss://im.example.com/ws \
  -Pim.hideServer=true
```

| 参数 | 默认值 | 说明 |
|---|---|---|
| `im.api` | `http://127.0.0.1:10002` | 默认 API 地址。**显式设置后 Web 端不再按页面域名自动推导** |
| `im.ws` | `ws://127.0.0.1:10001/ws` | 默认 WS 地址 |
| `im.hideServer` | `false` | 为 `true` 时登录页**完全不显示**服务器地址入口，用户无法改 |

也可以写进 `client/gradle.properties`，或用环境变量 `IM_APP_API` / `IM_APP_WS` / `IM_APP_HIDE_SERVER`（便于 CI 注入）。

实现方式：Gradle 任务 `generateImConfig` 把参数写成一个生成的 Kotlin 文件 
(`composeApp/build/generated/imconfig/im/app/ImBuildConfig.kt`)，业务代码只读 `ImBuildConfig`。
参数是任务的输入，改了必然重新生成 —— 不会出现「改了没生效」的缓存问题。

登录页填入服务端 API/WS 地址即可（默认 127.0.0.1:10002 / :10001）。

### 从旧版本升级：root 连库 → 最小权限账号

应用此前用 **root** 连库（能读 `mysql.user`、能碰任意库）。现在改用只对 `im` 库有权限的
`im` 账号。MySQL 的初始化目录**只在数据目录为空时执行**，所以已有部署需要手工建一次号：

```bash
# 1) .env 里补两个新变量，并删掉旧的 IM_MYSQL_PASSWORD
#    IM_MYSQL_ROOT_PASSWORD=<原来的 IM_MYSQL_PASSWORD>
#    IM_APP_PASSWORD=<新生成一个，openssl rand -hex 16>

# 2) 建号（在容器内执行，口令从容器环境变量取；外层用单引号避免宿主机提前展开）
docker compose -f deploy/docker-compose.yml exec mysql sh -c '
  mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "
    CREATE USER IF NOT EXISTS '"'"'im'"'"'@'"'"'%'"'"' IDENTIFIED BY '"'"'<第 1 步生成的 IM_APP_PASSWORD>'"'"';
    ALTER USER '"'"'im'"'"'@'"'"'%'"'"' IDENTIFIED BY '"'"'<第 1 步生成的 IM_APP_PASSWORD>'"'"';
    GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, DROP, REFERENCES ON im.* TO '"'"'im'"'"'@'"'"'%'"'"';
    FLUSH PRIVILEGES;"'

# 3) 重启
docker compose -f deploy/docker-compose.yml up -d
```

> 全新部署不需要这步：mysql 容器首次初始化时会自动执行 `deploy/init/01-create-app-user.sh`。
>
> 这条命令**可以重复执行**：里面除了 `CREATE USER IF NOT EXISTS` 还有一条 `ALTER USER`，
> 所以改了 `IM_APP_PASSWORD` 之后再跑一次，库里的口令会同步更新。
> 没有那条 `ALTER` 的话，`CREATE USER IF NOT EXISTS` 对已存在的账号是静默空操作，
> 口令不会变，应用连不上库且没有任何提示 —— 这是升级时最容易踩的坑。
>
> 若容器启动时报 `Access denied`：入口脚本会在重试 30 秒后直接退出并打印该提示，
> 按上面的建号步骤处理即可（不会像以前那样无声无息地一直卡在 unhealthy）。

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
| `IM_OIDC_RETURN_ALLOWLIST` | 空 | OIDC 登录成功后允许回跳的目标前缀（逗号分隔）。**留空 = 只允许本站同源**：浏览器授权完落在 `/oidc-done`、需用户手工复制令牌；填 `http://127.0.0.1,http://localhost,yanshu:` 可让桌面端与 Android **授权完自动登录**（Web 端本来同源，无需配置）。这是开放重定向的唯一防线，只放行信任目标 |
| `IM_TRUST_PROXY` | `false` | 置 `true` 才信任 `X-Forwarded-For` / `X-Forwarded-Host`（放在 Nginx/Caddy 后面时需要）。**只支持一层可信反代**：取 XFF 最后一段；且来自代理头的地址不再享受 loopback 限流豁免 |
| `IM_AUTH_RATE_LIMIT` | `60` | 登录/注册每 IP、5 分钟内允许的次数；`0` 关闭。**仅直连的 loopback 不计数**（本机开发/单测不受影响）；若所有客户端都从同一 NAT 出口进来，请调大该值 |

OIDC 的 `redirect_uri` 默认必须等于 `{base}/v1/oidc/{name}/callback`；需要额外回跳地址时，
在管理后台「站点配置」的 OIDC provider 里配 `redirect_uris` 白名单。

站点相关的（好友申请开关、雁书号规则、OIDC、Turnstile、邮箱验证码）在管理后台
「站点配置」里改，落在数据库中，进程启动时读取。

## 测试

CI（`.github/workflows/ci.yml`）在每次 push/PR 上跑：Go 构建 + vet + 单测、protoc 生成物与
`im.proto` 的一致性、客户端编译与单元测试、服务端镜像构建，以及**完整 E2E**
（起 MySQL/Redis/MinIO + 三进程，跑客户端 5 个 E2E 用例）。本地对应命令如下。

```bash
cd server && go test ./...                 # 服务端单测（含协议 golden 校验）
cd server && go vet ./...

# 跨进程链路冒烟：需先起 MySQL/Redis 并运行 logic/gateway/admin
# （compose 里已带 MySQL/Redis，起完后三个进程的环境变量见 docker-compose.yml）
go test -tags smoke ./cmd/gateway -run TestSmoke -v

cd client && ./gradlew :shared:desktopTest   # KMP 客户端单测 + E2E（E2E 需先起好服务端）

# 改了 proto 之后重建双端金样本
./proto/gen.sh --update
cd client && UPDATE_CLIENT_GOLDEN=1 gradle :shared:desktopTest --tests 'im.client.proto.ProtoGoldenTest'
```

客户端 E2E（`ImClientE2eTest` / `Phase2E2eTest` / `Phase3E2eTest`）需要本地起好
MySQL/Redis + logic + gateway，并预先注册 `alice` / `bob`（密码 `secret1`）。
服务端不在默认端口时用 `IM_TEST_API` / `IM_TEST_WS` 指定；
本地库目录可用 `IM_CLIENT_DB_DIR` 重定向（**建议测试时指定**，否则切账号会清掉 `~/.yanshu` 里真实的本地缓存）：

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
- Android 依赖 **AGP 兼容模式**：AGP 9 起 `com.android.*` 与 `org.jetbrains.kotlin.multiplatform`
  不再直接兼容，当前用官方给的 `android.builtInKotlin=false` + `android.newDsl=false` 绕过。
  长期方案是把 `shared` 迁到 `com.android.kotlin.multiplatform.library`、
  把 `composeApp` 拆成「非 KMP 的 app 模块 + KMP UI 库」
- Android/桌面端的 Turnstile 仍是空实现，站点一旦开启 Turnstile 这两端将无法注册登录
- 归档 key 已改为随机串，管理后台通过 `archive_log.object_key` 查看；旧版本用
  `archive/<日期>/<会话ID>.jsonl` 落下的历史对象不在新白名单内，需要人工迁移或清理

## 许可证

本项目基于 [AGPL-3.0](LICENSE) 开源：修改后的服务端若对网络提供服务，须以同许可证开源。商业闭源集成需另行授权。
