#!/usr/bin/env bash
# DSH on Android — APK 构建脚本
#
# 依赖：
#   - aapt2、d8.jar、apksigner.jar（Termux 或 Android build-tools）
#   - JDK（javac / java）
#   - android.jar（Android SDK platform，放到本目录或设置 ANDROID_JAR）
#
# 用法：
#   KS=/path/to/my.keystore KS_PASS='******' ./build.sh
#
# 可覆盖的环境变量：PREFIX AAPT2 D8_JAR APKSIGNER_JAR ANDROID_JAR JAVAC JAVA OUT ALIAS KS KS_PASS
set -euo pipefail
cd "$(dirname "$0")"

PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
AAPT2="${AAPT2:-$PREFIX/bin/aapt2}"
D8_JAR="${D8_JAR:-$PREFIX/share/java/d8.jar}"
APKSIGNER_JAR="${APKSIGNER_JAR:-$PREFIX/share/java/apksigner.jar}"
ANDROID_JAR="${ANDROID_JAR:-$PWD/android.jar}"
JAVAC="${JAVAC:-javac}"
JAVA="${JAVA:-java}"
OUT="${OUT:-dsh.apk}"
BUILD="${BUILD:-build}"
ALIAS="${ALIAS:-dsh}"
KS="${KS:-}"
KS_PASS="${KS_PASS:-}"

for f in "$ANDROID_JAR" "$AAPT2" "$D8_JAR" "$APKSIGNER_JAR"; do
  [ -f "$f" ] || { echo "缺少依赖：$f"; exit 1; }
done
if [ -z "$KS" ] || [ -z "$KS_PASS" ]; then
  cat <<'TIP'
必须通过环境变量提供签名信息：
  KS       keystore 路径
  KS_PASS  keystore 密码

生成一个（并妥善备份，丢了就只能卸载重装）：
  keytool -genkeypair -v -keystore release.keystore -alias dsh \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass '<your-pass>' -keypass '<your-pass>' \
    -dname "CN=DSH Android, O=DSH, C=CN"
TIP
  exit 1
fi
[ -f "$KS" ] || { echo "keystore 不存在：$KS"; exit 1; }

rm -rf "$BUILD"
mkdir -p "$BUILD/classes" "$BUILD/dex"

echo "[1/6] 编译资源"
"$AAPT2" compile --dir res -o "$BUILD/res.zip"

echo "[2/6] 链接资源与清单"
"$AAPT2" link \
  -o "$BUILD/base.apk" \
  -I "$ANDROID_JAR" \
  --manifest AndroidManifest.xml \
  -A assets \
  --min-sdk-version 26 \
  --target-sdk-version 28 \
  "$BUILD/res.zip"

echo "[3/6] 编译 Java"
find src -name '*.java' > "$BUILD/sources.txt"
"$JAVAC" -encoding UTF-8 -source 8 -target 8 -nowarn \
  -classpath "$ANDROID_JAR" -d "$BUILD/classes" @"$BUILD/sources.txt"

echo "[4/6] dex"
find "$BUILD/classes" -name '*.class' > "$BUILD/classes.txt"
"$JAVA" -cp "$D8_JAR" com.android.tools.r8.D8 --lib "$ANDROID_JAR" \
  --min-api 26 --output "$BUILD/dex" @"$BUILD/classes.txt"

echo "[5/6] 打包"
cp "$BUILD/base.apk" "$BUILD/unsigned.apk"
(cd "$BUILD" && zip -q -j unsigned.apk dex/classes.dex)

echo "[6/6] 签名"
"$JAVA" -jar "$APKSIGNER_JAR" sign \
  --ks "$KS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --ks-key-alias "$ALIAS" \
  --out "$OUT" "$BUILD/unsigned.apk"
"$JAVA" -jar "$APKSIGNER_JAR" verify --print-certs "$OUT" | head -5
ls -lh "$OUT"
echo "完成：$(pwd)/$OUT"
