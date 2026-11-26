#!/usr/bin/env bash
# 导出一份数据库备份（压缩的 SQL），并清掉过期的旧备份。每日定时的写法见 backup.cron.example。
#   deploy/backup.sh
# 直接用本机的 mysqldump 连本机 MySQL 8.4（无 Docker），库名、主机、端口、root 密码读 deploy/.env。
# 环境变量（都可不设）：
#   EGG_BACKUP_DIR        备份放哪里，默认 deploy/backups/
#   EGG_BACKUP_KEEP_DAYS  保留几天，默认 14
# 备份只存在这台机器上；要防整机损坏，请把备份目录同步到另一台机器或网盘（自己安排，这里不做）。
#
# 恢复（会覆盖现有数据，先停后端；库名、账号与 .env 一致）：
#   先停掉后端（Ctrl+C 掉 deploy/run-backend.sh，或 systemctl stop egg-backend）
#   gunzip -c deploy/backups/egg-XXXXXXXX-XXXXXX.sql.gz | MYSQL_PWD=<root 密码> mysql -h127.0.0.1 -uroot --default-character-set=utf8mb4 egg
#   再启动后端
set -euo pipefail
# 备份里有账号与密码哈希，只让当前用户读
umask 077

here=$(cd "$(dirname "$0")" && pwd)
dir=${EGG_BACKUP_DIR:-$here/backups}
keep=${EGG_BACKUP_KEEP_DAYS:-14}
if [ -f "$here/.env" ]; then
  set -a
  # shellcheck disable=SC1091
  . "$here/.env"
  set +a
fi
db=${EGG_DB_NAME:-egg}
host=${EGG_DB_HOST:-127.0.0.1}
port=${EGG_DB_PORT:-3306}
command -v mysqldump >/dev/null || { echo "这台机器上没有 mysqldump：装 MySQL 8.4 时一并装上客户端工具" >&2; exit 1; }

mkdir -p "$dir"
stamp=$(date +%Y%m%d-%H%M%S)
out="$dir/egg-$stamp.sql.gz"
tmp="$out.part"
trap 'rm -f "$tmp"' EXIT

# 密码经 MYSQL_PWD 传，不出现在命令行上；--single-transaction 导出时不锁表，收蛋照常
MYSQL_PWD="${EGG_DB_ROOT_PASSWORD:-root}" mysqldump -h"$host" -P"$port" -uroot \
  --single-transaction --routines --default-character-set=utf8mb4 "$db" \
  | gzip > "$tmp"

# mysqldump 正常结束时最后一段是 "Dump completed"；没有就说明中途断了，不留下一份看着正常的坏备份
if ! gunzip -c "$tmp" | tail -n 5 | grep -q "Dump completed"; then
  echo "备份失败：导出内容不完整，已丢弃。先确认本机 MySQL 在运行、deploy/.env 里的 EGG_DB_ROOT_PASSWORD 正确" >&2
  exit 1
fi
mv "$tmp" "$out"
echo "已备份：$out（$(du -h "$out" | cut -f1)）"

find "$dir" -maxdepth 1 -name 'egg-*.sql.gz' -mtime "+$keep" -print -delete | sed 's/^/已清理过期备份：/'
