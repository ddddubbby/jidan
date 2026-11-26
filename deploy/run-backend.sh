#!/usr/bin/env bash
# 在本机直接起后端（无 Docker）：读 deploy/.env，连本机 MySQL 8.4，用 JDK 17 运行打好的 jar。
#   deploy/run-backend.sh
# 第一次先 cp deploy/.env.example deploy/.env 并按说明改好；没有 .env 时全部用默认值
# （本机 127.0.0.1:3306 的 egg 库，账号 egg/egg，模拟好生意，带演示数据）。
# 还没打包时自动打一次（跳过测试；测试由开发时的 make verify 负责）。
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
root=$(cd "$here/.." && pwd)

if [ -f "$here/.env" ]; then
  set -a
  # shellcheck disable=SC1091
  . "$here/.env"
  set +a
  # 与原来的 compose.yml 一致：有 .env 但没写这一项时按正式部署处理，不写演示数据
  export EGG_SEED_DEMO=${EGG_SEED_DEMO:-false}
fi

# 后端只认 EGG_DB_URL；没直接给时用 .env 里的主机、端口、库名拼出来
: "${EGG_DB_URL:=jdbc:mysql://${EGG_DB_HOST:-127.0.0.1}:${EGG_DB_PORT:-3306}/${EGG_DB_NAME:-egg}?characterEncoding=utf8&connectionTimeZone=Asia/Shanghai}"
export EGG_DB_URL
# 中文姓名等环境变量要在 UTF-8 区域设置下读（见 .env.example）
export LANG=${LANG:-C.UTF-8} TZ=Asia/Shanghai

jar=$(ls "$root"/backend/target/egg-backend-*.jar 2>/dev/null | grep -v '\.original$' | head -n 1 || true)
if [ -z "$jar" ]; then
  echo "还没有打包好的后端，先打包一次（./mvnw -DskipTests package）…"
  (cd "$root/backend" && ./mvnw -B -q -DskipTests package)
  jar=$(ls "$root"/backend/target/egg-backend-*.jar | grep -v '\.original$' | head -n 1)
fi

exec java -jar "$jar"
