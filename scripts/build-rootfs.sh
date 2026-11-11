#!/usr/bin/env bash
# 在 arm64 Ubuntu rootfs 内构建可用的运行环境，并打包成 rootfs.tar
# 用法（在 rootfs 内，root 身份）：bash build-rootfs.sh /output/rootfs.tar
set -euo pipefail

OUT="${1:-/output/rootfs.tar}"
NODE_MAJOR="${NODE_MAJOR:-24}"
PROFILE="${PROFILE:-phone}"

echo "[1/6] 基础依赖"
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq curl ca-certificates python3 python3-pip python3-venv git xz-utils \
  libstdc++6 zip unzip procps >/dev/null

echo "[2/6] Node.js ${NODE_MAJOR}"
if ! command -v node >/dev/null 2>&1 || [ "$(node -p 'process.versions.node.split(".")[0]')" -lt "$NODE_MAJOR" ]; then
  curl -fsSL "https://deb.nodesource.com/setup_${NODE_MAJOR}.x" | bash - >/dev/null
  apt-get install -y -qq nodejs >/dev/null
fi
node -v; npm -v

echo "[3/6] 安装 DSH"
npm install -g @deepseek-ai/dsh >/dev/null
dsh --version

echo "[4/6] 创建 profile: ${PROFILE}（从 shipped 模板 web 复制并启动一次）"
if [ ! -d "$HOME/.dsh/profiles/$PROFILE" ]; then
  ( timeout 90 dsh --profile "$PROFILE" --from-default-profile web --port 3099 --no-open >/tmp/profile-boot.log 2>&1 || true )
  sleep 2
  pkill -f "dsh --profile $PROFILE" 2>/dev/null || true
  sleep 1
fi
[ -d "$HOME/.dsh/profiles/$PROFILE" ] || { echo "❌ profile 创建失败"; tail -20 /tmp/profile-boot.log; exit 1; }

echo "[5/6] 清理"
apt-get clean
rm -rf /var/lib/apt/lists/* /var/cache/apt/archives/* /tmp/* /root/.npm /root/.cache 2>/dev/null || true
find /usr/share/doc /usr/share/man -mindepth 1 -maxdepth 1 -exec rm -rf {} + 2>/dev/null || true

echo "[6/6] 打包 -> $OUT"
mkdir -p "$(dirname "$OUT")"
cd /
tar cf "$OUT" \
  --exclude=./proc --exclude=./sys --exclude=./dev --exclude=./tmp --exclude=./run \
  --exclude=./mnt --exclude=./sdcard --exclude=./storage --exclude=./media \
  --exclude=./system --exclude=./system_ext --exclude=./vendor --exclude=./product \
  --exclude=./odm --exclude=./apex --exclude=./data --exclude=./.l2s \
  --exclude=./var/cache ./root/.cache .
ls -lh "$OUT"
echo "✅ 完成：$OUT"
