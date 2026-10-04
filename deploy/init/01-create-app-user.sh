#!/bin/sh
# 创建最小权限的应用账号（仅在 mysql 数据目录为空、首次初始化时执行）。
#
# 应用不该用 root 连库：root 能读 mysql.user、能碰任意库、还有 FILE/SUPER 等特权，
# 一旦应用侧凭据泄露，影响面是整个数据库实例而不是一个库。
#
# 已有部署（数据目录已初始化）不会被再次执行，需要手工跑一次：
#   docker compose -f deploy/docker-compose.yml exec mysql \
#     sh /docker-entrypoint-initdb.d/01-create-app-user.sh
set -e

mysql -uroot -p"$MYSQL_ROOT_PASSWORD" <<SQL
CREATE USER IF NOT EXISTS 'im'@'%' IDENTIFIED BY '${IM_APP_PASSWORD}';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, DROP, REFERENCES ON im.* TO 'im'@'%';
FLUSH PRIVILEGES;
SQL
echo "[init] 最小权限账号 im 已就绪（仅 im 库权限）"
