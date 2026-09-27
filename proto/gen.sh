#!/usr/bin/env bash
# 协议生成与一致性校验。
#
# 本仓库的协议事实来源是 proto/im.proto：
#   - 服务端：protoc 生成 Go 代码到 server/internal/pb（本脚本负责）
#   - 客户端：手写编解码 client/shared/.../proto/Frames.kt（字段号必须与 im.proto 对齐）
#
# 因为客户端没有 codegen，用 proto/golden/*.bin 做跨语言一致性校验：
#   1) 本脚本重新生成 Go 代码并跑 go test（含 golden 比对）；
#   2) 客户端跑 :shared:desktopTest 中的 ProtoGoldenTest 解码同一批 golden。
#
# 用法：
#   ./proto/gen.sh            # 生成 + 校验
#   ./proto/gen.sh --update   # 修改 im.proto 后重建 golden（需要同步更新客户端期望值）
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROTO_DIR="$ROOT/proto"
OUT_DIR="$ROOT/server/internal/pb"

UPDATE=0
[[ "${1:-}" == "--update" ]] && UPDATE=1

if ! command -v protoc >/dev/null 2>&1; then
  echo "错误: 未找到 protoc，先安装 protobuf 编译器" >&2
  exit 1
fi
if ! command -v protoc-gen-go >/dev/null 2>&1; then
  echo "错误: 未找到 protoc-gen-go，先执行:" >&2
  echo "  go install google.golang.org/protobuf/cmd/protoc-gen-go@latest" >&2
  exit 1
fi

echo "==> 生成 server/internal/pb"
protoc -I "$PROTO_DIR" \
  --go_out="$OUT_DIR" --go_opt=paths=source_relative \
  "$PROTO_DIR/im.proto"

echo "==> go build"
(cd "$ROOT/server" && go build ./...)

echo "==> go test"
if [[ "$UPDATE" -eq 1 ]]; then
  (cd "$ROOT/server" && go test ./internal/pb -run TestGolden -update)
else
  (cd "$ROOT/server" && go test ./...)
fi

echo "==> 完成"
echo "    若改动了 im.proto 的字段号/枚举值，还需同步修改客户端 Frames.kt，"
echo "    并让 :shared:desktopTest 里的 ProtoGoldenTest 通过。"
