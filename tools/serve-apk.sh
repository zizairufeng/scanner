#!/usr/bin/env bash
#
# 一键启动 ScanStock 内网下载服务。
#
#   bash tools/serve-apk.sh                # 前台运行，Ctrl+C 停止
#   PORT=9000 bash tools/serve-apk.sh      # 换端口
#   bash tools/serve-apk.sh --host 192.168.2.233   # 只绑指定地址
#
# 后台常驻（关掉终端也不停）：
#   nohup bash tools/serve-apk.sh > /tmp/serve-apk.log 2>&1 &
#   查看地址：grep 监听 -A3 /tmp/serve-apk.log
#   停止：    pkill -f serve-apk.js
#
# 为什么需要这个脚本（而不是直接敲 node tools/serve-apk.js）：
# 换机器之后 node 不一定在 PATH 里（本项目的隔离工具链装在自己的目录下），
# 而且沙箱/容器里跑 node 还会撞上注入的 NODE_OPTIONS（指向一个不存在的
# preload 模块，Node 启动即 MODULE_NOT_FOUND）。这两件事都在这里处理掉。
set -euo pipefail

HERE="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
ROOT="$(cd -- "$HERE/.." && pwd)"

# ── 找 node ────────────────────────────────────────────────────────────
# 顺序：显式指定 > PATH > 本机 WorkBuddy 托管目录 > 仓库自带的便携 node
find_node() {
  if [ -n "${NODE_BIN:-}" ] && [ -x "${NODE_BIN}" ]; then echo "${NODE_BIN}"; return; fi
  if command -v node >/dev/null 2>&1; then command -v node; return; fi
  local candidate
  for candidate in \
    "$HOME"/.workbuddy/binaries/node/versions/*/bin/node \
    "$ROOT"/../.wbtools/node/node-v*-linux-x64/bin/node \
    /usr/bin/node /usr/local/bin/node
  do
    [ -x "$candidate" ] && { echo "$candidate"; return; }
  done
}

NODE="$(find_node || true)"
if [ -z "${NODE:-}" ]; then
  echo "找不到 node。请装一个（apt install nodejs）或指定路径：" >&2
  echo "  NODE_BIN=/path/to/node bash tools/serve-apk.sh" >&2
  exit 1
fi

# 容器/沙箱里会被注入 NODE_OPTIONS，指向宿主 App 的 preload 模块；
# 那个路径在容器里不存在，Node 会在**启动阶段**就 MODULE_NOT_FOUND 退出
# （报错看着像脚本坏了，其实跟脚本无关）。这里检测到就忽略它。
if [ -n "${NODE_OPTIONS:-}" ]; then
  shim="$(printf '%s' "$NODE_OPTIONS" | grep -oE '/[^"[:space:]]+\.(cjs|js)' | head -1 || true)"
  if [ -n "$shim" ] && [ ! -e "$shim" ]; then
    echo "提示：忽略注入的 NODE_OPTIONS（它指向的 $shim 在本机不存在）"
    unset NODE_OPTIONS
  fi
fi

echo "node : $NODE"
echo "工程 : $ROOT"
echo

exec "$NODE" "$ROOT/tools/serve-apk.js" "$@"
