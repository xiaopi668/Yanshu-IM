#!/bin/sh
# 等待 MySQL 就绪并应用 schema，然后按 IM_ROLE 启动对应服务
set -e

MYSQL_HOST="${IM_MYSQL_HOST:-mysql}"
MYSQL_PORT="${IM_MYSQL_PORT:-3306}"
DSN_HOST_PORT="tcp(${IM_MYSQL_HOST:-mysql}:${IM_MYSQL_PORT:-3306})"
export IM_MYSQL_DSN="${IM_MYSQL_DSN:-root:${IM_MYSQL_PASSWORD:-root123}@${DSN_HOST_PORT}/im?parseTime=true}"
export IM_REDIS_ADDR="${IM_REDIS_ADDR:-redis:6379}"

echo "[entrypoint] waiting for mysql..."
until mysql -h"$IM_MYSQL_HOST" -uroot -p"$IM_MYSQL_PASSWORD" -e "SELECT 1" >/dev/null 2>&1; do
  sleep 2
done
# 多个容器可能同时启动并重复执行 DDL，IF NOT EXISTS 冲突属正常，但真实错误不能静默吞掉
if mysql -h"$IM_MYSQL_HOST" -uroot -p"$IM_MYSQL_PASSWORD" < /usr/local/share/im/schema.sql 2>/tmp/im-schema.err; then
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
