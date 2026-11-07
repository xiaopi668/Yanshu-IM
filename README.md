<img src="client/composeApp/src/commonMain/composeResources/drawable/logo.png" alt="雁书" width="92" align="right">

# 雁书 Yanshu

自托管的跨平台即时通讯：**Android / Windows / Linux / Web** 四端。
服务端 Go（三个进程共享 MySQL + Redis，无 RPC），客户端 Kotlin Multiplatform + Compose Multiplatform，
音视频走自托管 LiveKit SFU。

[![CI](https://github.com/xiaopi668/Yanshu-IM/actions/workflows/ci.yml/badge.svg)](https://github.com/xiaopi668/Yanshu-IM/actions/workflows/ci.yml)

| 想做什么 | 去哪看 |
|---|---|
| 5 分钟跑起来 | [快速开始](#快速开始) |
| 打包客户端 / 改默认服务器地址 | [客户端构建](#客户端构建) |
| 配环境变量 | [服务端配置](#服务端配置) |
| 部署与升级 | [部署与运维](#部署与运维) |
| 开两步验证 / 通行密钥 | [账号安全](#账号安全) |
| **了解「为什么这么设计」** | [docs/DESIGN.md](docs/DESIGN.md) |

## 功能

**消息**

- 单聊、群聊（写扩散）；会话 seq + ACK + 离线增量拉补，重连不丢不重
- 多媒体：图片 / 文件 / 语音 / 视频，预签名直传对象存储
- 会话列表、未读数、消息历史、按会话搜索
- 朋友圈：好友可见动态流，文字 + 九宫格图片、点赞、评论
- 通讯录：好友申请（附言）→ 同意/拒绝、备注、删除；按雁书号精确搜索

**通话**

- 1v1 语音 / 视频，自托管 LiveKit SFU（内嵌 TURN）
- 各端能力如实标注，不支持的平台会**明说**，不让人对着无声界面发呆

| 平台 | 语音 | 视频画面 |
|---|---|---|
| Web | 支持 | 支持 |
| Android | 支持 | 支持（前后摄像头切换、本地镜像） |
| 桌面 | 支持（内置 Rust 侧车） | 不支持，只做音频 |

**账号与安全**

- 注册 / 登录（bcrypt + JWT）、多端在线、按 IP 限流
- 登录标识三合一：用户名 / 雁书号 / 邮箱
- **两步验证（TOTP）**：扫码或手填密钥绑定、10 个一次性恢复码、登录第二因子
- **通行密钥（Passkey / WebAuthn）**：指纹/面容/设备 PIN 免密登录，支持无用户名流程
- 后台封禁对 **WS 与 REST 同时生效**；改密/重置密码使已签发 JWT **立即失效**

**运维**

- 三个进程都有 `/healthz`（会 ping MySQL/Redis）与 `/metrics`（Prometheus）
- 镜像带构建版本，`/healthz` 返回它，可与提交一一对应
- 每日自动把前一天消息按会话导出 JSONL 归档到对象存储；后台可手动触发
- 对象存储可插拔：MinIO / S3 / R2 / OSS（配 region 与 path-style）

## 快速开始

```bash
# 1. 生成密钥（compose 里所有密钥都是必填，缺哪个会直接报错并写出变量名）
cp deploy/.env.example deploy/.env
#   openssl rand -hex 32  → IM_JWT_SECRET / IM_ADMIN_TOKEN / IM_LIVEKIT_API_SECRET
#   openssl rand -hex 16  → IM_APP_PASSWORD / IM_MYSQL_ROOT_PASSWORD / IM_MINIO_ROOT_PASSWORD

# 2. 起服务端（镜像可从 GHCR 拉，见「部署与运维」；也可本地构建）
docker compose -f deploy/docker-compose.yml up -d
```

| 端点 | 地址 | 说明 |
|---|---|---|
| HTTP API | `http://<host>:10002/v1/...` | 对外 |
| WebSocket | `ws://<host>:10001/ws` | 对外 |
| LiveKit | `ws://<host>:7880` | 对外，生产请用 wss |
| 管理后台 | `http://127.0.0.1:10003/admin` | 只绑本机，走 SSH 隧道或反代加 TLS |
| MinIO | `http://127.0.0.1:9000` | 只绑本机（含全部附件与归档） |

注册两个账号即可互通：

```bash
curl -X POST http://127.0.0.1:10002/v1/register -d '{"username":"alice","password":"secret1"}'
curl -X POST http://127.0.0.1:10002/v1/register -d '{"username":"bob","password":"secret1"}'
```

> 为什么密钥必须填：早期版本给所有密钥留了「开发期默认值」，照着启动的生产环境等于
> JWT 密钥 / 管理令牌 / MinIO 口令全是公开已知值。

## 架构

```
client/                    KMP 客户端
├── shared/                协议（手写 protobuf 编解码）+ 连接层 + 本地缓存 + 消息/通话核心
│   ├── androidMain / desktopMain / wasmJsMain    平台 actual（网络、文件、渲染、权限）
└── composeApp/            一套 Compose UI 跑四端

server/                    Go 后端（零 RPC：三进程共享 MySQL + Redis）
├── cmd/gateway/           WebSocket 网关（鉴权/心跳/出站队列/投递/通话信令状态机）
├── cmd/logic/             HTTP API（注册登录/好友/会话/历史/预签名/OIDC/两步验证/通行密钥）
├── cmd/admin/             管理后台（封禁/统计/归档），Token 登录
└── internal/              auth / messaging / call / hub / storage / migrate

desktop-call/              桌面端通话侧车（Rust，JSON-lines 协议，音频）
proto/im.proto             协议单一事实来源；proto/golden/*.bin 双端一致性金样本
deploy/                    docker-compose / livekit.yaml / turn / init
```

| 进程 | 职责 | 端口 |
|---|---|---|
| `gateway` | WS 长连接、下行投递、通话信令 | 10001 |
| `logic` | REST API、OIDC、账号安全 | 10002 |
| `admin` | 管理后台 | 10003 |

三进程之间没有 RPC：共用 MySQL / Redis，下行帧通过 **Redis Pub/Sub** 广播。
消息可靠性、失败可见性、可观测性、令牌撤销这些「为什么」都在 [docs/DESIGN.md](docs/DESIGN.md)。

## 客户端构建

```bash
cd client
./gradlew :composeApp:desktopRun                  # 桌面（Windows/Linux）
./gradlew :composeApp:wasmJsBrowserDevelopmentRun # Web
./gradlew :composeApp:assembleDebug               # Android APK（需要 Android SDK）
```

**Android 前置条件**（不满足时 Android 目标会被自动跳过，只构建 desktop/wasm —— 有意为之，方便只做桌面/Web 开发）：

| 项 | 要求 |
|---|---|
| JDK | 17+ |
| `ANDROID_HOME` | 指向 Android SDK（未设置时跳过 Android） |
| SDK 组件 | `platforms;android-37.0` + `build-tools;37.0.0` |
| Gradle / AGP | Gradle 9.6+、AGP 9.4.x（wrapper 已配好） |

### APK 产物（按 ABI 拆分）

`io.livekit:livekit-android` 会带进四个 ABI 的 WebRTC 原生库（每个 7–15MB），因此产出 5 个包：

| 产物 | 大小（debug） | 用途 |
|---|---|---|
| `composeApp-arm64-v8a-debug.apk` | ~31 MB | **绝大多数手机装这个** |
| `composeApp-armeabi-v7a-debug.apk` | ~26 MB | 老 32 位设备 |
| `composeApp-x86_64-debug.apk` | ~35 MB | 模拟器 |
| `composeApp-x86-debug.apk` | ~30 MB | 老模拟器 |
| `composeApp-universal-debug.apk` | ~69 MB | 全量包：不用问架构 |

mips / mips64 / armeabi 已废弃，由 `ndk.abiFilters` 从源头过滤（否则全量包里仍会带上）。

### 构建期配置

私有化部署可把服务端地址**固化进客户端**，登录页不再暴露地址入口：

```bash
./gradlew :composeApp:assembleDebug \
  -Pim.api=https://im.example.com \
  -Pim.ws=wss://im.example.com/ws \
  -Pim.hideServer=true
```

| 参数 | 默认值 | 说明 |
|---|---|---|
| `im.api` | `http://127.0.0.1:10002` | 显式设置后 Web 端不再按页面域名推导 |
| `im.ws` | `ws://127.0.0.1:10001/ws` | 默认 WS 地址 |
| `im.hideServer` | `false` | `true` 时登录页完全不显示服务器地址入口 |

也可写进 `client/gradle.properties`，或用环境变量 `IM_APP_API` / `IM_APP_WS` / `IM_APP_HIDE_SERVER`（便于 CI 注入）。

### 版本号

不写死，按优先级解析：`-PverName/-PverCode` → `IM_APP_VERSION[_CODE]` → 最近 git tag（`vX.Y.Z`）→ `0.1.0/1`。

```bash
./gradlew :composeApp:printVersion                       # 看解析结果与来源
./gradlew :composeApp:assembleDebug -PverName=1.2.0      # versionCode 自动推导
```

Android 要求 `versionCode` 单调递增，写死会导致覆盖安装被拒；用 tag 推导后 `v0.1.0→100`、`v0.1.1→101` 自然递增。

## 服务端配置

三个进程读同一组环境变量（`deploy/docker-compose.yml` 已给默认值）：

| 变量 | 默认 | 说明 |
|---|---|---|
| `IM_MYSQL_DSN` / `IM_REDIS_ADDR` / `IM_REDIS_PASS` | 本地开发值 | 三进程必须一致 |
| `IM_JWT_SECRET` | **必填** | 登录令牌签名密钥；缺失或仍是默认值时拒绝启动 |
| `IM_ADMIN_TOKEN` | **必填** | 管理后台令牌（仅 admin 角色校验） |
| `IM_LIVEKIT_API_SECRET` | **必填** | 音视频密钥（仅 gateway 校验，真正签发 token） |
| `IM_ENV` | 空 | `production` 等同强制强密钥，用默认值直接拒绝启动 |
| `IM_NODE_ID` | gateway=1 / logic=2 | 雪花 ID 节点号；**多副本必须各不相同**，否则 ID 碰撞 |
| `IM_PUBLIC_BASE_URL` | 从请求推断 | OIDC 回跳与附件票据的对外基地址，**生产必须显式设置** |
| `IM_ALLOWED_ORIGINS` | 不限 | CORS / WS 的 Origin 白名单；不设 = 宽松 |
| `IM_OIDC_RETURN_ALLOWLIST` | 空 | 允许回跳的目标前缀；留空 = 只允许同源。**开放重定向的唯一防线** |
| `IM_TRUST_PROXY` | `false` | 置 `true` 才信任 `X-Forwarded-For/Host`（只支持一层可信反代） |
| `IM_AUTH_RATE_LIMIT` | `60` | 登录/注册每 IP 每 5 分钟次数；`0` 关闭 |
| `IM_WEBAUTHN_RP_ID` / `IM_WEBAUTHN_ORIGINS` / `IM_WEBAUTHN_RP_NAME` | 从 `IM_PUBLIC_BASE_URL` 推导 | 通行密钥的 RP ID / origin，见 [账号安全](#账号安全) |

站点相关开关（好友申请、雁书号规则、OIDC、Turnstile、邮箱验证码）在管理后台「站点配置」里改，落在数据库。

## 部署与运维

### 服务端镜像

CI 会把镜像推到 GitHub Container Registry（公开，无需登录即可拉）：

```bash
docker pull ghcr.io/xiaopi668/yanshu-im-server:latest
docker pull ghcr.io/xiaopi668/yanshu-im-server:sha-提交短哈希   # 可复现，生产建议用这个
```

把 `deploy/docker-compose.yml` 里的 `image: im-server:latest` 换成上面的地址即可。
生产建议锁 `sha-*`：`latest` 会跟着 main 变，某次 `docker compose pull` 就把服务端换掉了。

### 客户端产物

CI 的 `android` job 会把 5 个 APK 打成 `composeApp-debug-apk` 上传到 Actions run 的 Artifacts。
下载需要登录 GitHub，且有过期时间。

### 健康检查与指标

```bash
curl -s http://127.0.0.1:10002/healthz   # 会 ping MySQL/Redis，异常返回 503
curl -s http://127.0.0.1:10002/metrics   # Prometheus 文本格式
```

compose 里三个服务都配了基于 `/healthz` 的 healthcheck —— `docker ps` 直接看出实例是否健康。
指标清单见 [docs/DESIGN.md](docs/DESIGN.md)。

### 从旧版本升级：root 连库 → 最小权限账号

应用此前用 **root** 连库；现在改用只对 `im` 库有权限的账号。MySQL 初始化目录只在数据目录为空时执行，
已有部署需要手工建一次号（**全新部署不需要**，首次初始化会自动执行 `deploy/init/01-create-app-user.sh`）：

1. `.env` 里补两个变量：`IM_MYSQL_ROOT_PASSWORD`（原来那个口令）、`IM_APP_PASSWORD`（新生成），并删掉旧的 `IM_MYSQL_PASSWORD`
2. 在 mysql 容器内用 root 建号并授权到 `im.*`（具体 SQL 见 `deploy/init/01-create-app-user.sh`，把变量替换成第 1 步的值）
3. `docker compose up -d` 重启

> 建号语句要同时包含 `CREATE USER IF NOT EXISTS` 与 `ALTER USER`：只写前者，对已存在的账号是静默空操作 ——
> 口令不会变、应用连不上库且没有任何提示。这是升级时最容易踩的坑。

## 账号安全

### 两步验证（TOTP）

「我的资料 → 两步验证」：扫码（或手填 base32 密钥）绑定 → 填一次验证器当前的 6 位码确认 →
一次性展示 **10 个恢复码**（手机丢了靠它登录，每个只能用一次，库里只存 SHA-256）。

- 恢复码**只显示一次**，请当场保存；用完就只能找管理员
- 关闭需要当前验证码**或**一个恢复码
- 登录时密码与第二因子分两次判定，但对外都是 401，响应体用 `totp_required` 区分 ——
  客户端据此显示验证码输入框，而不是提示「密码错误」

接口：`GET /v1/me/totp`、`POST /v1/me/totp/setup|enable|disable`；登录接口增加可选字段 `totp_code`。

### 通行密钥（Passkey / WebAuthn）

先配好站点对外地址（**必需**，RP ID / origin 与它绑定）：

```bash
IM_PUBLIC_BASE_URL=https://im.example.com
# 反代 / 多域名时显式覆盖：
# IM_WEBAUTHN_RP_ID=im.example.com
# IM_WEBAUTHN_ORIGINS=https://im.example.com,https://im.example.org
```

「我的资料 → 通行密钥」点「绑定」→ 系统弹窗完成指纹/面容 → 登录页出现「使用通行密钥登录」。

- 登录页**先填账号**会走 allowCredentials，不填则走无用户名（discoverable）流程
- 绑定必须是 discoverable + 用户验证（`residentKey=required`），否则无用户名登录找不到凭据
- 通行密钥本身是「持有设备 + 生物识别」双因子，用它登录时不再要求 TOTP
- **Web 端已实现**；Android / 桌面暂不支持（桌面无系统级 WebAuthn API）
- 配置错了浏览器直接 `SecurityError` 且不说是哪个字段：常见原因是 `IM_PUBLIC_BASE_URL` 还是 http，
  或用了 IP 而不是域名（`localhost` 与 `127.0.0.1` 是两个不同的 RP ID）

## 测试

CI（`.github/workflows/ci.yml`）每次 push/PR 跑 7 个 job：`server`（构建 + vet + 单测 + 格式）、
`proto`（生成物一致性）、`client`（编译 + 单测）、`android`（打包 APK）、`docker`（构建并推 GHCR）、
`rust-sidecar`、`e2e`（真起 MySQL/Redis/MinIO + 三进程）。

```bash
cd server && go test ./... && go vet ./...

# 跨进程链路冒烟（需先起 MySQL/Redis + 三进程）
go test -tags smoke ./cmd/gateway -run TestSmoke -v

cd client && ./gradlew :shared:desktopTest    # KMP 单测 + E2E（E2E 需先起服务端并注册 alice/bob）

# 改了 proto 后重建双端金样本
./proto/gen.sh --update
cd client && UPDATE_CLIENT_GOLDEN=1 gradle :shared:desktopTest --tests 'im.client.proto.ProtoGoldenTest'
```

E2E 需预注册 `alice` / `bob`（密码 `secret1`）；服务端不在默认端口时用 `IM_TEST_API` / `IM_TEST_WS`，
本地缓存目录用 `IM_CLIENT_DB_DIR` 重定向（**建议指定**，否则切账号会清掉 `~/.yanshu` 里的真实缓存）。

## 已知限制

- 通话会话状态（`internal/call`）与后台在线数统计仍是**单进程内存**，多副本只在本副本准确；
  消息投递与 seq 已是跨进程正确的
- **多副本必须给每个实例设不同的 `IM_NODE_ID`**，否则雪花 ID 碰撞
- **桌面端无视频**：侧车只做音频（Rust + libwebrtc），视频要另走内嵌浏览器或帧传输
- **Android / 桌面不支持通行密钥**（需 Credential Manager + Digital Asset Links / 无系统 API）
- **Android/桌面端 Turnstile 是空实现**：站点一旦开启 Turnstile，这两端将无法注册登录
- 通话信令（`CallSignal`）自身没有失败应答，忙/拒接仍靠 `CALL_BUSY` / `CALL_REJECT` 表达
- 服务端没有「已读回执」下行帧，只有客户端 → 服务端的 `MsgRead`
- 朋友圈按 `moment.id`（雪花，字典序）分页，2031 年前等价于时间序
- 对象存储归档的旧 key（`archive/日期/会话ID.jsonl`）不在新白名单内，需要人工迁移或清理
- TLS 需在反代层终止，服务端只监听明文 HTTP/WS
- 尚未实现：Android 系统推送（ntfy/FCM）、端到端加密
- Android 依赖 **AGP 兼容模式**（`android.builtInKotlin=false` + `android.newDsl=false`）；
  长期方案是把 `shared` 迁到 `com.android.kotlin.multiplatform.library`

## 许可证

基于 [MIT](LICENSE) 开源：可自由使用、修改、分发与商用，保留版权声明即可。

> 从 AGPL-3.0 改为 MIT：AGPL 的传染性条款（网络提供服务即须开源）对私有化部署并不友好。
