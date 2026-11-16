#!/usr/bin/env bash
# 发布一个 APK 作为“最新版本”：拷进 deploy/apk/，并写 version.json。不用重启，站点马上能下载，
# APP 在登录页与建单页查到比自己新的 versionCode 就显示下载入口（开发规格 2.7）。
#   deploy/publish-apk.sh 路径/xxx.apk                       有 aapt2 时版本号从 APK 里读
#   deploy/publish-apk.sh 路径/xxx.apk 2 1.0.1                没有 aapt2 时手填：versionCode 和 versionName
# 站点只有一个更新入口，指向最近一次发布的那个 APK。模拟版与实机版包名不同、互不升级，
# 一个站点上通常只发其中一个（试用发模拟版，正式用发实机版）。
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
apk=${1:?用法：deploy/publish-apk.sh 路径/xxx.apk [versionCode versionName]}
[ -f "$apk" ] || { echo "找不到文件：$apk" >&2; exit 1; }
name=$(basename "$apk")
if [[ ! "$name" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*\.apk$ ]]; then
  echo "文件名只能用字母、数字、点、横线、下划线，且以 .apk 结尾：$name" >&2
  exit 1
fi

code=${2:-}
vname=${3:-}
if [ -z "$code" ] || [ -z "$vname" ]; then
  aapt2=$(command -v aapt2 || true)
  if [ -z "$aapt2" ] && [ -n "${ANDROID_HOME:-}" ]; then
    aapt2=$(ls "$ANDROID_HOME"/build-tools/*/aapt2 2>/dev/null | sort -V | tail -n 1 || true)
  fi
  if [ -z "$aapt2" ]; then
    echo "这台机器上没有 aapt2，读不出版本号。请手填：deploy/publish-apk.sh $apk <versionCode> <versionName>" >&2
    exit 1
  fi
  badging=$("$aapt2" dump badging "$apk" | grep '^package:')
  code=$(sed -n "s/.* versionCode='\([0-9]*\)'.*/\1/p" <<<"$badging")
  vname=$(sed -n "s/.* versionName='\([^']*\)'.*/\1/p" <<<"$badging")
fi
[[ "$code" =~ ^[0-9]+$ ]] || { echo "versionCode 必须是整数：$code" >&2; exit 1; }
[[ "$vname" =~ ^[A-Za-z0-9._+-]+$ ]] || { echo "versionName 里有不允许的字符：$vname" >&2; exit 1; }

dir=$here/apk
mkdir -p "$dir"
# 先拷文件再写 version.json，APP 读到新版本号时文件一定已经在了
if [ "$(cd "$(dirname "$apk")" && pwd)/$name" != "$dir/$name" ]; then
  cp "$apk" "$dir/$name"
fi
# __BASE__ 由 nginx 换成手机访问站点用的地址（见 nginx.conf）
printf '{"versionCode":%s,"versionName":"%s","downloadUrl":"__BASE__/apk/%s"}\n' "$code" "$vname" "$name" > "$dir/version.json.part"
mv "$dir/version.json.part" "$dir/version.json"
echo "已发布：$name（versionCode $code，versionName $vname）"
