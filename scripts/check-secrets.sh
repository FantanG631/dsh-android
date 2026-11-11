#!/usr/bin/env bash
# 提交前的敏感信息自检：命中即失败
set -u
cd "$(dirname "$0")/.." || exit 1
PATTERNS=(
  'sk-[A-Za-z0-9]{20,}'                 # API key
  'token=[A-Za-z0-9_-]{16,}'            # 登录 token
  '1[3-9][0-9]{9}'                      # 手机号
  '(QQ|qq|uin|群号)[^0-9]{0,4}[1-9][0-9]{5,10}'  # 上下文里的 QQ 号
  'onebot11_[0-9]+'
  'BEGIN (RSA |EC )?PRIVATE KEY'
)
fail=0
for p in "${PATTERNS[@]}"; do
  hits=$(grep -rInE "$p" --exclude-dir=.git --exclude=.gitignore . 2>/dev/null | head -5)
  if [ -n "$hits" ]; then
    echo "⚠️  命中模式: $p"
    echo "$hits"
    fail=1
  fi
done
[ "$fail" = 0 ] && echo "✅ 未发现明显敏感信息" || { echo "❌ 请先处理上述命中项"; exit 1; }
