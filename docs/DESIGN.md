# 设计说明

这份文档记录**为什么这么设计**。README 只讲怎么用。[返回 README](../README.md)

---

## 跨进程投递：三进程没有 RPC

gateway / logic / admin 各自持有同一个 `hub`，通过 **Redis Pub/Sub（`im:push`）**广播下行帧 ——
logic 上发起的事件（好友申请、群邀请、后台封禁踢线后的状态同步等）经总线送到 gateway 上的在线连接。
发布方按实例 ID 跳过自己，避免本地重复投递。

为什么不做 RPC：三个进程本来就共享 MySQL/Redis，再引入一套 RPC 只会多一层故障面与部署依赖；
而「谁在线」这件事只有 gateway 知道，广播 + 各自过滤是最小代价的做法。

## 消息可靠性模型

参考 OpenIM / WuKongIM：

- 每个会话单调递增 seq，由 Redis Lua 脚本分配，**以 DB 的 `MAX(seq)` 为下界自愈** ——
  重启、切换实例都不会回退
- 发送方收到 ACK 后记录 `server_msg_id` / `seq`
- 接收方断线重连后按「本地 max seq → 服务端最新」增量拉补（`MsgPullReq/Resp`），保证不丢不重
- 客户端重试用 `client_msg_id` 幂等去重

## 失败必须可见（不允许静默丢消息）

协议 `Frame` 里有 `Error`（字段 12）。服务端对任何 C→S 请求失败（不是会话成员、落库/Redis 失败、
不支持的帧类型）都会回一帧带 `client_msg_id` 的失败应答，**绝不只写服务端日志**；
客户端收到后立刻把消息标成「发送失败，点击重试」。

客户端侧还有一道兜底：发送后 10 秒未收到 ACK 自动标失败，重连成功后按 `client_msg_id`
自动补发所有未确认消息（服务端幂等去重，不会产生重复消息）。

> 这条是踩过坑后加的：早期发送失败只写日志，客户端只能永远停在「发送中」，
> 既不知道失败也不会重发 —— 也就是「静默丢消息」。

## 可观测性

三个进程都提供：

- `/healthz`：会 ping MySQL 与 Redis，异常返回 503
- `/metrics`：Prometheus 文本格式

指标：

| 指标 | 含义 |
|---|---|
| `im_gateway_online_conns` | 在线连接数 |
| `im_ws_connections_total` | 建连总数 |
| `im_ws_auth_failures_total{reason}` | 鉴权失败 |
| `im_msg_send_errors_total{code}` | 发送/拉取失败 |
| `im_gateway_send_queue_full_total` | 出站队列打满 |
| `im_pubsub_publish_errors_total` | 跨进程广播失败 |
| `im_http_requests_total{role,code}` | HTTP 请求分布 |
| `im_uptime_seconds` / `im_goroutines` / `im_memory_*` | 进程自身 |

**构建版本可追溯**：镜像构建时 `--build-arg VERSION=$(git rev-parse --short HEAD)` 注入，
进程启动打印 `version=...`，`/healthz` 也会返回它。

## 账号状态与令牌撤销

每个 HTTP 请求都会校验 `user_state`（是否封禁 + 令牌版本），**不止登录和 WS 首帧** ——
否则封禁对 REST 形同虚设。

校验结果缓存在 Redis（`im:ustate:<uid>`），后台封禁/启用/重置密码时**主动失效缓存**，因此立即生效。
WS 连接由 gateway 每 10s 巡检一次，发现封禁或令牌版本落后就断开。

重置密码会 `token_version+1`，该账号此前签发的所有 JWT 当场作废（不是等自然过期）。

## 登录语义

- 登录标识按 **用户名 → 雁书号 → 邮箱** 顺序解析（三者都可填在同一个输入框）
- `user.email` **没有唯一索引**：同一邮箱可能被多个账号绑定。这种情况下返回 409 并提示
  「该邮箱绑定了多个账号，请改用用户名或雁书号登录」，而**不会随便挑一个**
  （那会造成「用我的邮箱登进别人账号」）
- 邮箱不存在与密码错误都返回同一句 `bad credentials`，避免变成账号枚举接口
- 登录与注册是两个独立界面：关站时不给「去注册」入口，但仍能退回登录

## OIDC 登录：令牌怎么回到客户端

默认不需要用户复制令牌，按平台分三种：

- **Web**：页面本身是回调目标（同源），服务端把令牌放在 URL fragment，页面加载时读取并**立即从地址栏抹掉**
- **桌面**：客户端起本地回环监听器（`127.0.0.1` 随机端口，RFC 8252），服务端以 query 回跳，
  浏览器显示「登录成功，可以关闭此页」
- **Android**：自定义 scheme `yanshu://oidc/callback`（`singleTask` + `onNewIntent`）

桌面与 Android 的回跳目标不在本站同源范围，需要在 `IM_OIDC_RETURN_ALLOWLIST` 显式放行；
**没配置时自动退回「浏览器展示令牌 + 用户粘贴」的老流程**，不会把人锁在门外。
这个白名单是开放重定向的唯一防线。

## 附件下载：不让账号凭证随消息扩散

消息里只存对象存储 key（`Attachment.url` 承载 key）。取用时调用方用**自己的**登录态向
`POST /v1/attachments/ticket` 换一张短时票据（HMAC 签名、绑定单个 key、10 分钟过期），
再 `GET /v1/download?key=..&ticket=..`。

授权判定覆盖三种引用来源：会话消息（须是该会话成员）、用户头像（站内可见）、朋友圈图片（作者本人或好友）。

`/v1/download` 本身不校验登录态 —— 票据就是这次下载的凭证，因为「下载」按钮会在系统浏览器里
`openUrl`、带不上 `Authorization` 头。但 key 必须命中生成端白名单，归档对象（`archive/`）不在其中，
因此「按日期 + 会话 ID 推导 key 拖走聊天归档」的路径是关闭的。

## 聊天记录归档

每日 03:00 把前一天的消息按会话导出为 JSONL 到对象存储；后台可手动触发任意日期并查看清单。
key 已改为随机串（管理后台通过 `archive_log.object_key` 查看）—— 早期版本用可推导的
`archive/<日期>/<会话ID>.jsonl`，那等于给归档开了一条无需授权的旁路。

## 两步验证（TOTP）实现要点

- 服务端自研 RFC 6238（HMAC-SHA1 / 30s / 6 位），用 RFC 附录 B 的官方向量做单测
- 校验允许 ±1 个步长：手机与服务器时钟常有几十秒偏差，不放宽会「刚生成的码就说不对」
- 比对用 `subtle.ConstantTimeCompare~
- 恢复码 4 字节随机 → 形如 `ab12-cd34`，入库前 SHA-256（库被拖走也不能直接登录）；
  不用 bcrypt 是因为恢复码本身是 32 位随机串，而登录要对表逐个比对，bcrypt 的慢反而能被利用来打 DoS
- 二维码**在服务端生成**（`skip2/go-qrcode`，本地编码）：三端共用一份实现，
  更要紧的是**绝不能用在线二维码 API** —— 那等于把 TOTP 密钥发给第三方
- 所有校验接口都走 IP 限流：6 位数字只有一百万种，不限速就是可爆破的

## 通行密钥（WebAuthn）实现要点

- 用 [go-webauthn](https://github.com/go-webauthn/webauthn) 做协议校验（CBOR / COSE / 签名 / origin / 计数器）。
  这块**不适合手写**：任何一处写错都是「看起来能用、实际可绕过」
- 挑战存 Redis、5 分钟过期、**取一次就删**（防重放）
- 凭据整体以 JSON 落库（`user_passkey.credential_json`），库升级不用改表
- 注册必须 `residentKey=required` + `userVerification=required`：
  用 `preferred` 时认证器可以把凭据存成**非 discoverable**，那样无用户名登录根本找不到它
  （症状：绑定看起来成功，登录永远提示「未找到此应用程序的通行密钥」）
- 登录先填账号走 `allowCredentials`，不填才走 discoverable —— 前者同时兼容早期建的非 resident 凭据
- 通行密钥本身是「持有设备 + 生物识别/PIN」双因子，用它登录时不再要求 TOTP

## 视频渲染：Compose 画不了视频

Compose 画在 canvas 上，**放不了 video**。三端各自承载，统一接口 `CallVideoSurface(role)`：

- **Android**：`AndroidView` + LiveKit 的 `SurfaceViewRenderer`（原生图层，**不能**包进会裁剪的容器，否则黑屏）
- **Web**：占位 Box 回传位置（`onGloballyPositioned` + density 换算），浏览器用 fixed 定位的 DOM 视频层渲染
- **桌面**：占位（侧车只做音频）

几个必须守住的点：

- **本地预览必须 muted**，否则自己的声音从扬声器再进麦克风会啸叫
- 渲染器要**双向补挂**：View 先到/轨道后到、轨道先到/View 后到，两种情况都要能挂上 ——
  少一种就是「一直黑屏但有声音」
- 镜像只作用于**本地预览**，绝不能镜像发布出去的流（对方看到的字会是反的）
- Android 前台服务类型要同时含 `microphone` 与 `camera`，与清单一致；
  不一致会在**打开摄像头那一刻**抛 SecurityException

## MySQL 最小权限

应用此前用 root 连库（能读 `mysql.user`、能碰任意库），现在改用只对 `im` 库有权限的账号。
MySQL 的初始化目录只在数据目录为空时执行，所以老部署要手工建一次号 —— 见 README 的「从旧版本升级」。
