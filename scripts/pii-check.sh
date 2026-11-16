#!/usr/bin/env bash
# 扫描提交内容里的个人信息，命中就以非 0 退出。规则见开发规格 14.3。
#   scripts/pii-check.sh              扫描暂存区里改动的文件
#   scripts/pii-check.sh all          扫描全部已入库文件
#   scripts/pii-check.sh --file 路径   扫描一个文件（commit-msg hook 用）
# 只属于某个人的词（昵称、邮箱等）写在仓库根目录的 .pii-local 里，一行一个；该文件不入库。
set -u
root=$(git rev-parse --show-toplevel)
cd "$root" || exit 2

case "${1:-}" in
  all)    files=$(git ls-files) ;;
  --file) files=$2 ;;
  *)      files=$(git diff --cached --name-only --diff-filter=ACMR) ;;
esac
files=$(printf '%s\n' "$files" | grep -v -x -e 'scripts/pii-check.sh' -e '' || true)
[ -z "$files" ] && exit 0

generic='/Users/[^/[:space:]<]+/|/home/[^/[:space:]<]+/|claude\.ai/code/session_[0-9A-Za-z]|session_[0-9A-Za-z]{8,}|originSessionId|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}'
allow='noreply@anthropic\.com|@example\.(com|org)|\.invalid\b|-0000-1000-8000-00805f9b34fb'

hits=$(printf '%s\n' "$files" | while IFS= read -r f; do
  [ -f "$f" ] || continue
  grep -I -n -H -E "$generic" -- "$f" 2>/dev/null | grep -v -E "$allow"
  if [ -s .pii-local ]; then
    grep -v -e '^[[:space:]]*$' -e '^#' .pii-local > "${TMPDIR:-/tmp}/pii-local.$$"
    grep -I -n -H -i -F -f "${TMPDIR:-/tmp}/pii-local.$$" -- "$f" 2>/dev/null
    rm -f "${TMPDIR:-/tmp}/pii-local.$$"
  fi
done)

if [ -n "$hits" ]; then
  echo "发现可能的个人信息，已拒绝提交（规则见开发规格 14.3）："
  printf '%s\n' "$hits" | cut -c1-200
  exit 1
fi
