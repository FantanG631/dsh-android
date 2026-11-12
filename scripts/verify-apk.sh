#!/usr/bin/env bash
# APK 发布前断言 + 交付清单生成。任何断言失败即 exit 1。
# 用法：bash verify-apk.sh <apk> [期望签名指纹前缀]
#
# 想额外扫描自定义口令（例如你本机的 keystore 密码），用环境变量注入，
# 不要把它硬编码进本文件：
#   FORBIDDEN='你的口令片段' bash verify-apk.sh app.apk 114f796a
set -uo pipefail
APK="${1:?用法: verify-apk.sh <apk> [cert-prefix]}"
EXPECT_CERT="${2:-}"
[ -f "$APK" ] || { echo "❌ 文件不存在: $APK"; exit 1; }
fail=0
say() { printf '%s\n' "$*"; }

PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
AAPT2="${AAPT2:-$PREFIX/bin/aapt2}"
APKSIGNER_JAR="${APKSIGNER_JAR:-$PREFIX/share/java/apksigner.jar}"
JAVA="${JAVA:-java}"

DEX_FILE="$(mktemp)"
trap 'rm -f "$DEX_FILE"' EXIT
unzip -p "$APK" classes.dex > "$DEX_FILE" 2>/dev/null

# 1. 占位符
if grep -aq '<owner>/<repo>' "$DEX_FILE"; then
  say "❌ ROOTFS_URL 仍是占位符 <owner>/<repo>"; fail=1
fi
# 2. 疑似密钥（内置通用模式；自有口令请用 FORBIDDEN 注入）
PAT='sk-[A-Za-z0-9]{16,}|ghp_[A-Za-z0-9]{20,}|BEGIN [A-Z ]*PRIVATE KEY'
if [ -n "${FORBIDDEN:-}" ]; then PAT="$PAT|$(printf '%s' "$FORBIDDEN" | sed 's/[][\.*^$/]/\\&/g')"; fi
if grep -aqE "$PAT" "$DEX_FILE"; then
  say "❌ dex 中疑似含密钥/口令"; fail=1
fi
# 3. 基本信息
BADGE="$("$AAPT2" dump badging "$APK" 2>/dev/null | head -2)"
say "--- basic ---"; say "$BADGE"
VC="$(printf '%s' "$BADGE" | grep -o "versionCode='[0-9]*'" | grep -o '[0-9]*' | head -1)"
VN="$(printf '%s' "$BADGE" | grep -o "versionName='[^']*'" | head -1)"
# 4. 签名
CERT="$("$JAVA" -jar "$APKSIGNER_JAR" verify --print-certs "$APK" 2>/dev/null | grep -o 'SHA-256 digest: [0-9a-fA-F]*' | head -1 | awk '{print $3}')"
say "cert sha256: $CERT"
if [ -n "$EXPECT_CERT" ] && [ -n "$CERT" ]; then
  case "$CERT" in "$EXPECT_CERT"*) ;; *) say "❌ 签名指纹与期望不符（期望前缀 $EXPECT_CERT）"; fail=1;; esac
fi
# 5. 清单
SHA="$(sha256sum "$APK" | awk '{print $1}')"
SZ="$(stat -c %s "$APK")"
say "--- manifest ---"
say "file: $(basename "$APK")  ${SZ} B  sha256=${SHA:0:16}…"
say "versionCode=$VC  $VN"
say "placeholder=$(grep -ac '<owner>/<repo>' "$DEX_FILE" || true)  url=$(grep -ao 'https://github.com/[^"]*' "$DEX_FILE" | head -1)"
say "state.json=$(grep -ac 'state.json' "$DEX_FILE" || true)  RETRY=$(grep -ac 'com.dsh.phone.RETRY' "$DEX_FILE" || true)  UPDATE=$(grep -ac 'com.dsh.phone.UPDATE' "$DEX_FILE" || true)"

[ "$fail" = 0 ] && say "✅ verify 通过" || { say "❌ verify 失败"; exit 1; }

# 6. 文件名约定：纯 ASCII + 版本 + sha 前缀
BASE="$(basename "$APK")"
say "建议发布名: ${BASE%.apk}-${SHA:0:8}.apk"
