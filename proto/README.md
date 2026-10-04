# 协议

`im.proto` 是**唯一事实来源**：每条 WebSocket 消息 = 一个 `Frame`（Protobuf 编码）。
这里没有 gRPC service，logic ↔ gateway 之间也不走 RPC，而是共享 MySQL + Redis。

## 两端的实现方式不一样

| 端 | 实现 | 位置 |
|---|---|---|
| 服务端 | `protoc` 生成 | `server/internal/pb/im.pb.go` |
| 客户端 | **手写**编解码（只实现 varint / length-delimited 子集） | `client/shared/src/commonMain/kotlin/im/client/proto/{ProtoWriter,Frames}.kt` |

客户端没有 codegen，字段号与枚举值靠人工与 `im.proto` 对齐——这是本项目最大的协议漂移风险。

## 生成与校验

```bash
./proto/gen.sh            # 重新生成 Go 代码 + 跑全部测试（含 golden 比对）
./proto/gen.sh --update   # 修改了 im.proto，重建 proto/golden/*.bin
```

依赖：`protoc`、`protoc-gen-go`（`go install google.golang.org/protobuf/cmd/protoc-gen-go@latest`）。

## golden 文件

`proto/golden/*.bin` 是由 Go 侧（protoc 生成的实现）编码出来的样例帧，
覆盖 Frame 的全部 11 个 oneof 分支、map 字段、嵌套 message 与两个枚举。

- **服务端**：`server/internal/pb/golden_test.go` 编码后逐字节比对，并做解码回环校验。
- **客户端**：`client/shared/src/desktopTest/.../ProtoGoldenTest.kt` 解码同一批文件并断言字段值。

任何一端的字段号/枚举值漂移都会让其中一边失败。

## Attachment.url 的语义

`Attachment.url` 承载的是**对象存储 key**（`image/20261003/<16位hex>`），不是可直接访问的地址。
取用流程：`POST /v1/attachments/ticket`（带登录态）换短时票据 → `GET /v1/download?key=..&ticket=..`。

原因：这个字段会被写进 `message.attachment`、广播给会话全部成员、并进入聊天归档。
早期实现往里塞了 `/v1/download?key=..&token=<7天有效的登录 JWT>`，等于把发送者的账号凭证
发给了每一个能看到这条消息的人。**任何"把长期凭证放进消息"的改动都不要再做。**

## 修改协议的步骤

1. 改 `proto/im.proto`（**字段号只增不改，枚举值只增不删**，否则破坏兼容）
2. `./proto/gen.sh --update`
3. 同步改客户端 `Frames.kt`，更新 `ProtoGoldenTest` 的期望值
4. `cd client && gradle :shared:desktopTest`
