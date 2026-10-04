#!/bin/sh
# 等待 MySQL 就绪并应用 schema，然后按 IM_ROLE 启动对应服务
set -e

# 应用账号：默认使用最小权限的 im 账号，而不是 root。
# root 只留给 mysql 容器自身（建账号、初始化），应用侧拿到的凭据只对 im 库有效，
# 即便泄露也读不到 mysql.user、碰不了其它库、更没有 FILE/SUPER 这类特权。
MYSQL_USER="${IM_MYSQL_USER:-im}"

# 拒绝内置默认库口令：compose 已强制显式设置，裸跑容器时也不该悄悄用弱口令
if [ -z "${IM_MYSQL_PASSWORD:-}" ] && [ -z "${IM_MYSQL_DSN:-}" ]; then
  echo "[entrypoint] 必须设置 IM_MYSQL_PASSWORD（或显式提供 IM_MYSQL_DSN）" >&2
  exit 1
fi

MYSQL_HOST="${IM_MYSQL_HOST:-mysql}"
MYSQL_PORT="${IM_MYSQL_PORT:-3306}"
DSN_HOST_PORT="tcp(${IM_MYSQL_HOST:-mysql}:${IM_MYSQL_PORT:-3306})"
export IM_MYSQL_DSN="${IM_MYSQL_DSN:-${MYSQL_USER}:${IM_MYSQL_PASSWORD}@${DSN_HOST_PORT}/im?parseTime=true}"
export IM_REDIS_ADDR="${IM_REDIS_ADDR:-redis:6379}"

echo "[entrypoint] waiting for mysql (user=$MYSQL_USER)..."
until mysql -h"$IM_MYSQL_HOST" -u"$MYSQL_USER" -p"$IM_MYSQL_PASSWORD" -e "SELECT 1" >/dev/null 2>&1; do
  sleep 2
done
# 多个容器可能同时启动并重复执行 DDL，IF NOT EXISTS 冲突属正常，但真实错误不能静默吞掉
if mysql -h"$IM_MYSQL_HOST" -u"$MYSQL_USER" -p"$IM_MYSQL_PASSWORD" < /usr/local/share/im/schema.sql 2>/tmp/im-schema.err; then
  echo "[entrypoint] schema applied"
else
  echo "[entrypoint] 警告: schema 执行有错误（多容器并发建表时常见，可忽略；否则需排查）:"
  sed 's/^/    /' /tmp/im-schema.err
fi

case "${IM_ROLE}" in
  gateway)
    exec /usr/local/bin/im-gateway
    ;;
  admin)
    exec /usr/local/bin/im-admin
    ;;
  *)
    exec /usr/local/bin/im-logic
    ;;
esac
