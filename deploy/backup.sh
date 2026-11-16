#!/usr/bin/env bash
# 导出一份数据库备份（压缩的 SQL），并清掉过期的旧备份。每日定时的写法见 backup.cron.example。
#   deploy/backup.sh
# 环境变量（都可不设）：
#   EGG_BACKUP_DIR        备份放哪里，默认 deploy/backups/
#   EGG_BACKUP_KEEP_DAYS  保留几天，默认 14
# 备份只存在这台机器上；要防整机损坏，请把备份目录同步到另一台机器或网盘（自己安排，这里不做）。
#
# 恢复（会覆盖现有数据，先停后端；库名、账号与 .env 一致）：
#   docker compose -f deploy/compose.yml stop web backend
#   gunzip -c deploy/backups/egg-XXXXXXXX-XXXXXX.sql.gz | docker compose -f deploy/compose.yml exec -T mysql \
#     sh -c 'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot --default-character-set=utf8mb4 "$MYSQL_DATABASE"'
#   docker compose -f deploy/compose.yml start backend web
set -euo pipefail
# 备份里有账号与密码哈希，只让当前用户读
umask 077

here=$(cd "$(dirname "$0")" && pwd)
dir=${EGG_BACKUP_DIR:-$here/backups}
keep=${EGG_BACKUP_KEEP_DAYS:-14}
compose=(docker compose -f "$here/compose.yml")

mkdir -p "$dir"
stamp=$(date +%Y%m%d-%H%M%S)
out="$dir/egg-$stamp.sql.gz"
tmp="$out.part"
trap 'rm -f "$tmp"' EXIT

# 密码用容器里已有的环境变量，不出现在命令行上；--single-transaction 导出时不锁表，收蛋照常
"${compose[@]}" exec -T mysql sh -c \
  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysqldump -uroot --single-transaction --routines --default-character-set=utf8mb4 "$MYSQL_DATABASE"' \
  | gzip > "$tmp"

# mysqldump 正常结束时最后一段是 "Dump completed"；没有就说明中途断了，不留下一份看着正常的坏备份
if ! gunzip -c "$tmp" | tail -n 5 | grep -q "Dump completed"; then
  echo "备份失败：导出内容不完整，已丢弃。先确认数据库在运行：docker compose -f deploy/compose.yml ps" >&2
  exit 1
fi
mv "$tmp" "$out"
echo "已备份：$out（$(du -h "$out" | cut -f1)）"

find "$dir" -maxdepth 1 -name 'egg-*.sql.gz' -mtime "+$keep" -print -delete | sed 's/^/已清理过期备份：/'
