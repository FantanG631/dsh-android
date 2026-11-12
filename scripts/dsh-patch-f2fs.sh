#!/usr/bin/env bash
# 给 DSH 的会话/附件存储加「硬链接失败回退 copyFile」补丁（幂等）。
# 用途：npm 升级 @deepseek-ai/dsh 后必须重跑，否则 f2fs 上新建会话会 ENOENT。
# 用法（在 rootfs 内执行）：bash dsh-patch-f2fs.sh [模块根目录]
set -euo pipefail
ROOT="${1:-/usr/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai}"
[ -d "$ROOT" ] || { echo "找不到 DSH 模块目录：$ROOT"; exit 1; }

python3 - "$ROOT" <<'PY'
import re, sys, os
root = sys.argv[1]
files = [
    os.path.join(root, "dsh-session-persistence-jsonl/lib/index.js"),
    os.path.join(root, "dsh-attachment-local/lib/index.js"),
]
HELPER = '''
/** f2fs/沙箱下 link() 可能被拒：用 EXCL 拷贝兜底，保留 EEXIST 语义。 */
async function __linkOrCopy(a, b) {
  try {
    await link(a, b);
  } catch (error) {
    if (error && typeof error === "object" && error.code === "EEXIST") throw error;
    await copyFile(a, b, 1);
  }
}
'''
for path in files:
    if not os.path.exists(path):
        print("跳过（不存在）:", path); continue
    s = open(path).read()
    if '__linkOrCopy' in s:
        print("已打过补丁:", os.path.basename(os.path.dirname(os.path.dirname(path)))); continue
    m = re.search(r'import \{([^}]*)\} from "node:fs/promises";', s)
    if not m:
        print("!! 找不到 fs/promises 导入:", path); sys.exit(1)
    names = [n.strip() for n in m.group(1).split(',')]
    if 'copyFile' not in names:
        names.append('copyFile')
        s = s[:m.start()] + 'import { ' + ', '.join(names) + ' } from "node:fs/promises";' + s[m.end():]
    last = 0
    for mm in re.finditer(r'^import .*;$', s, re.M):
        last = mm.end()
    s = s[:last] + '\n' + HELPER + s[last:]
    if 'dsh-session-persistence' in path:
        s = s.replace('await link(tmp, finalPath);', 'await __linkOrCopy(tmp, finalPath);')
        s = s.replace('await internals.fs.link(staged, currentPath);', 'await __linkOrCopy(staged, currentPath);')
    else:
        s = s.replace('await link(source, target);', 'await __linkOrCopy(source, target);')
        s = s.replace('await link(staged.path, target);', 'await __linkOrCopy(staged.path, target);')
    open(path, 'w').write(s)
    print("已打补丁:", path)
PY
node --check "$ROOT/dsh-session-persistence-jsonl/lib/index.js"
node --check "$ROOT/dsh-attachment-local/lib/index.js"
echo "✅ 补丁完成（语法检查通过）"
