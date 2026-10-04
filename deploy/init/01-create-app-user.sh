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
-- 建库同样只能由 root 做（应用账号只有 im 库内的权限）
CREATE DATABASE IF NOT EXISTS im DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
CREATE USER IF NOT EXISTS 'im'@'%' IDENTIFIED BY '${IM_APP_PASSWORD}';
-- 关键：CREATE USER IF NOT EXISTS 对已存在的账号是**静默空操作**，
-- IDENTIFIED BY 会被忽略。手工重跑本脚本（或改了 IM_APP_PASSWORD）时，
-- 只有这句 ALTER 才能把口令改成当前值 —— 否则应用会一直 Access denied，且没有任何提示。
ALTER USER 'im'@'%' IDENTIFIED BY '${IM_APP_PASSWORD}';
GRANT SELECT, INSERT, UPDATE, DELETE, CREATE, ALTER, INDEX, DROP, REFERENCES ON im.* TO 'im'@'%';
FLUSH PRIVILEGES;
SQL
echo "[init] 最小权限账号 im 已就绪（仅 im 库权限）"
