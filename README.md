# 雁书 Yanshu — 自研跨平台即时通讯

支持 **Android / Windows / Linux / Web** 四端的私有化部署 IM。服务端 Go，客户端 Kotlin Multiplatform + Compose Multiplatform，音视频用自托管 LiveKit SFU。

## 功能

- 注册 / 登录（bcrypt + JWT）、多端在线
- 单聊、群聊（写扩散）、消息可靠投递（会话 seq + ACK + 离线拉补）
- 多媒体消息：图片 / 文件 / 语音 / 视频（MinIO 预签名直传）
- 1v1 音视频通话（自托管 LiveKit SFU + coturn TURN 打洞；Android / Web 端媒体接通，桌面端通话信令就绪）
- 会话列表、未读、消息历史拉取、离线增量同步

## 架构

```
client/                    KMP 客户端
├── shared/                协议(手写 protobuf 编解码) + 连接层 + 本地缓存 + 通话/消息核心
│   ├── androidMain / desktopMain / wasmJsMain   平台 actual（网络栈、文件选择、打开链接）
└── composeApp/            一套 Compose UI 跑四端

server/                    Go 后端
├── cmd/gateway/           WebSocket 长连接网关（鉴权/心跳/投递/通话信令状态机）
├── cmd/logic/             HTTP API（注册登录/好友/会话/历史/预签名）
└── internal/              auth(JWT/bcrypt) / messaging(seq+ACK+落库) / call / storage / hub

proto/im.proto             协议单一事实来源（Frame + CallSignal）

deploy/
├── docker-compose.yml     一键起全套
├── livekit.yaml           SFU 配置
└── turn/turnserver.conf   TURN 打洞配置
```

消息可靠性模型（参考 OpenIM/WuKongIM）：每个会话单调递增 seq（Redis INCR），发送方收到 ACK 记录 server_msg_id/seq；接收方断线重连后按「本地 max seq → 服务端最新」增量拉补（`MsgPullReq/Resp`），保证不丢不重。客户端重试用 `client_msg_id` 幂等去重。

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

## 测试

```bash
cd client && ./gradlew :shared:desktopTest   # KMP 客户端 E2E（单聊+群聊，需服务端在本地跑着）
cd server && go test ./...                   # 服务端单测
```

## 已知边界（二期）

- 桌面端音视频媒体（libwebrtc JNI 绑定）——信令与 UI 已就绪
- Web 端通话媒体（livekit-client JS interop）
- Android 系统推送（自建 ntfy/FCM 网关）
- E2E 加密、消息搜索、本地持久化(SQLDelight)

## 许可证

本项目基于 [AGPL-3.0](LICENSE) 开源：修改后的服务端若对网络提供服务，须以同许可证开源。商业闭源集成需另行授权。
