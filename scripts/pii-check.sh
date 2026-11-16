#!/usr/bin/env bash
# 扫描提交内容里的个人信息，命中就以非 0 退出。规则见开发规格 14.3。
#   scripts/pii-check.sh              扫描暂存区里改动的文件
#   scripts/pii-check.sh all          扫描全部已入库文件
#   scripts/pii-check.sh --file 路径   扫描一个文件（commit-msg hook 用）
# 文本文件逐行扫描；docx/xlsx/pptx 另查文档属性和正文，pdf 另查作者等元数据（见 scan_binary）。
# 其他二进制文件（图片等）不扫描，入库前需人工确认。
# 只属于某个人的词（昵称、邮箱等）写在仓库根目录的 .pii-local 里，一行一个；该文件不入库。
set -u
root=$(git rev-parse --show-toplevel)
cd "$root" || exit 2

case "${1:-}" in
  all)    files=$(git -c core.quotepath=off ls-files) ;;
  --file) files=$2 ;;
  *)      files=$(git -c core.quotepath=off diff --cached --name-only --diff-filter=ACMR) ;;
esac
files=$(printf '%s\n' "$files" | grep -v -x -e 'scripts/pii-check.sh' -e '' || true)
[ -z "$files" ] && exit 0

generic='/Users/[^/[:space:]<]+/|/home/[^/[:space:]<]+/|claude\.ai/code/session_[0-9A-Za-z]|session_[0-9A-Za-z]{8,}|originSessionId|[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\.[A-Za-z0-9-]+)*\.[A-Za-z]{2,}'
pdf_ok='Gprinter'   # 厂商手册里的公司名
allow='noreply@anthropic\.com|@example\.(com|org)|\.invalid\b|-0000-1000-8000-00805f9b34fb'

# Office 文档：自定义属性、作者字段、属性里的 base64 块、正文；PDF：作者/创建者字段。
scan_binary() {
  f=$1
  case "$f" in
    *.docx|*.xlsx|*.pptx|*.docm|*.xlsm|*.pptm)
      unzip -l "$f" 2>/dev/null | grep -q 'docProps/custom\.xml' &&
        echo "$f: 含自定义文档属性 docProps/custom.xml（常存账号、设备信息），请移除"
      unzip -p "$f" docProps/core.xml 2>/dev/null |
        grep -a -o -E '<(dc:creator|cp:lastModifiedBy)>[^<]+<' |
        sed "s|^|$f: 文档属性里有作者信息 |"
      unzip -p "$f" 'docProps/*' 2>/dev/null | grep -a -q -E '[A-Za-z0-9+/]{60,}={0,2}' &&
        echo "$f: 文档属性里有疑似 base64 块"
      unzip -p "$f" 2>/dev/null | grep -a -n -E "$generic" | grep -a -v -E "$allow" |
        sed "s|^|$f: 正文或属性 |"
      ;;
    *.pdf)
      grep -a -o -E '/Author[ ]*\([^)]+\)' "$f" 2>/dev/null | grep -a -v -E "$pdf_ok" |
        sed "s|^|$f: PDF 作者元数据 |"
      ;;
  esac
}

hits=$(printf '%s\n' "$files" | while IFS= read -r f; do
  [ -f "$f" ] || continue
  scan_binary "$f"
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
