#!/usr/bin/env bash
# 扫描提交内容里的个人信息，命中就以非 0 退出。规则见开发规格 14.3。
#   scripts/pii-check.sh              扫描暂存区里改动的文件，并检查提交身份（作者、提交者的邮箱须在允许名单内）
#   scripts/pii-check.sh all          扫描全部已入库文件
#   scripts/pii-check.sh --file 路径   扫描一个文件（commit-msg hook 用）
# 只属于某个人的词（昵称、邮箱等）写在仓库根目录的 .pii-local 里，一行一个；该文件不入库。
set -u
root=$(git rev-parse --show-toplevel)
cd "$root" || exit 2

# 提交身份检查：只在 pre-commit（无参数）时做。身份是每个克隆自己的配置，不随仓库走，
# 个人全局配置会把真实姓名和邮箱直接写进作者字段，所以这里拦一道。
ident_bad=0
if [ -z "${1:-}" ]; then
  allowed_idents='egg-dev@users.noreply.invalid noreply@anthropic.com'
  for kind in GIT_AUTHOR_IDENT GIT_COMMITTER_IDENT; do
    ident=$(git var "$kind" 2>/dev/null) || continue   # 没配身份时 git 自己会报错，这里不重复
    email=${ident#*<}; email=${email%%>*}
    case " $allowed_idents " in *" $email "*) ;; *)
      if [ "$ident_bad" = 0 ]; then
        echo "提交身份不在允许名单内，已拒绝提交（规则见开发规格 14.3）。"
        echo "允许的邮箱：egg-dev@users.noreply.invalid、noreply@anthropic.com。"
        echo "在本克隆（不要加 --global）执行一次："
        echo "  git config user.name egg-dev"
        echo "  git config user.email egg-dev@users.noreply.invalid"
      fi
      ident_bad=1
      echo "当前 ${kind#GIT_} 的邮箱：$email" ;;
    esac
  done
fi

case "${1:-}" in
  all)    files=$(git ls-files) ;;
  --file) files=$2 ;;
  *)      files=$(git diff --cached --name-only --diff-filter=ACMR) ;;
esac
files=$(printf '%s\n' "$files" | grep -v -x -e 'scripts/pii-check.sh' -e '' || true)
[ -z "$files" ] && exit "$ident_bad"

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
exit "$ident_bad"
