#!/usr/bin/env bash
#
# 局域网访问自检 + 修复（需 root）
#
#   sudo bash tools/lan-doctor.sh          # 默认查 8080
#   sudo bash tools/lan-doctor.sh 9000     # 查别的端口
#
# 它做三件事：
#   1. 列出本机所有可用的局域网地址，告诉你手机上到底该敲哪个；
#   2. 查防火墙（ufw / iptables / nftables）有没有把端口拦掉 —— 拦了就放行；
#   3. 给出「手机打不开」的结论和下一步。
#
# 为什么需要这个脚本：这台机器是 VMware 虚拟机，而「手机连不上」在虚拟机上有
# 两条完全不同的原因（虚拟机网络模式不对 / 客户机防火墙拦截），
# 从虚拟机内部是看不出区别的，得靠这个脚本把两边的状态都摊开。
set -uo pipefail

PORT="${1:-8080}"

echo "=============================================="
echo " ScanStock 局域网访问自检   端口 = $PORT"
echo "=============================================="
echo

# ── 0. 权限 & 环境 ────────────────────────────────────────────────────
if [ "$(id -u)" -ne 0 ]; then
  echo "⚠️  需要 root 才能检查/修改防火墙，请用：sudo bash $0 $PORT"
  echo "   （下面仍然会做只读检查）"
  echo
fi

IS_VM="否"
[ -n "$(cat /sys/class/dmi/id/product_name 2>/dev/null | grep -i vmware)" ] && IS_VM="是（VMware）"
# 兜底：容器/沙箱里 /sys 可能没挂载，就用主机名判断（VMware 客户机名字里常带 VMware）
if [ "$IS_VM" = "否" ] && echo "$(hostname)" | grep -qi vmware; then
  IS_VM="很可能是（主机名里带 VMware，且 /sys 不可读无法进一步确认）"
fi
echo "【1】本机网络"
echo "  主机名      : $(hostname)"
echo "  是否虚拟机  : $IS_VM"
echo "  IPv4 地址   :"
ip -4 -o addr show scope global 2>/dev/null | awk '{printf "                %-10s %s\n", $2, $4}'
GW="$(ip route 2>/dev/null | awk '/^default/{print $3; exit}')"
echo "  默认网关    : ${GW:-（没有默认路由）}"
echo "  网关 MAC    : $(ip neigh show "$GW" 2>/dev/null | awk '{print $5; exit}')"
echo
echo "  ⚠️ 如果上面只有 192.168.x.x 一个地址，且这个网段是 VMware 虚拟网络编辑"
echo "     器里 VMnet8/VMnet1 的网段，那手机根本路由不到这台机器 —— 见文末结论。"
echo

# ── 1. 端口有没有在监听 ───────────────────────────────────────────────
echo "【2】服务是否在监听 $PORT"
LISTEN="$(ss -lntp 2>/dev/null | grep -E "[:.]$PORT\b" || true)"
if [ -z "$LISTEN" ]; then
  echo "  ❌ 没有进程在监听 $PORT —— 先在项目目录里启动："
  echo "       bash tools/serve-apk.sh"
else
  echo "$LISTEN" | sed 's/^/  /'
  # ⚠️ 判据必须是「有没有**非回环**的监听」，不能是「有没有回环监听」。
  #    默认会同时绑 0.0.0.0(或具体网卡) + 127.0.0.1，用后者判会把正常情况误报成
  #    「只绑了回环」。这个 bug 第一版就写错过，是自检工具自己必须先被自检的典型例子。
  OTHER_BIND="$(echo "$LISTEN" | grep -vE '(127\.0\.0\.1|\[::1\]):'"$PORT"'\b' || true)"
  if echo "$LISTEN" | grep -qE '(\*|0\.0\.0\.0):'"$PORT"'\b'; then
    echo "  ✅ 绑在所有网卡上（0.0.0.0），局域网可访问"
  elif [ -n "$OTHER_BIND" ]; then
    echo "  ✅ 绑在具体网卡地址上，局域网可访问"
  else
    echo "  ⚠️ 只绑了回环 127.0.0.1，**手机访问不了**。改用："
    echo "       bash tools/serve-apk.sh            # 默认绑所有网卡 + 回环"
  fi
fi
echo

# ── 2. 防火墙 ─────────────────────────────────────────────────────────
echo "【3】防火墙"
FIREWALL_ACTIVE=0

if command -v ufw >/dev/null 2>&1; then
  UFW_STATE="$(ufw status 2>/dev/null | head -1)"
  echo "  ufw: $UFW_STATE"
  if echo "$UFW_STATE" | grep -qi active; then
    FIREWALL_ACTIVE=1
    if ufw status 2>/dev/null | grep -qE "^$PORT(/tcp)?\b.*ALLOW"; then
      echo "  ✅ 已放行 $PORT"
    else
      if [ "$(id -u)" -eq 0 ]; then
        echo "  → 未放行 $PORT，正在放行…"
        ufw allow "$PORT/tcp" && echo "  ✅ 已添加规则：ufw allow $PORT/tcp"
      else
        echo "  ❌ 未放行 $PORT（需要 sudo）"
      fi
    fi
  else
    echo "  ✅ ufw 未启用，不拦截"
  fi
else
  echo "  ufw: 未安装"
fi

if command -v iptables >/dev/null 2>&1; then
  BAD="$(iptables -S 2>/dev/null | grep -E -- "-p tcp.*--dport $PORT.*(DROP|REJECT)" || true)"
  if [ -n "$BAD" ]; then
    FIREWALL_ACTIVE=1
    echo "  iptables 里有 DROP/REJECT 规则："
    echo "$BAD" | sed 's/^/      /'
    echo "      （需要的话手动删掉对应规则再试）"
  else
    echo "  iptables: 没有针对 $PORT 的 DROP/REJECT 规则"
  fi
fi

if command -v nft >/dev/null 2>&1; then
  NFTRULE="$(nft list ruleset 2>/dev/null | grep -E "dport $PORT" || true)"
  [ -n "$NFTRULE" ] && { echo "  nftables 相关规则："; echo "$NFTRULE" | sed 's/^/      /'; }
fi
echo

# ── 3. 同网段还有谁（判断是不是虚拟网络）──────────────────────────────
echo "【4】同网段邻居（ARP 表）"
ip neigh show 2>/dev/null | sed 's/^/  /' | head -20
echo "  ↑ 如果这里只有「网关 + 一两个 00:0c:29 / 00:50:56 开头的 VMware 设备」，"
echo "    说明这台机器可能处在 VMware 的 NAT / 仅主机网段里 —— 手机是够不着的。"
echo

# ── 4. 结论 ───────────────────────────────────────────────────────────
echo "=============================================="
echo " 怎么定位（在手机浏览器里做，10 秒）"
echo "=============================================="
echo
echo "  用手机浏览器依次打开下面两个地址，看哪个能开："
echo
echo "    A) http://${GW:-192.168.2.1}          ← 路由器/网关"
echo "    B) http://<上面【1】里那个本机 IP>:$PORT/   ← 我们的下载页"
echo
echo "  结果怎么读："
echo "    A 能开、B 打不开   → 手机和电脑在同一网络，问题在**这台机器的防火墙**，"
echo "                        脚本已尝试放行；重跑一遍确认【3】里是 ✅ 即可。"
echo "    A 打不开           → **手机根本不在这个网络里**。说明电脑所在的是"
echo "                        VMware 的虚拟网段（NAT / 仅主机），手机路由不到。"
echo "                        两个选择："
echo "                          · 把虚拟机网络改成「桥接模式」，让电脑直接拿到"
echo "                            真实局域网（和手机同一个 Wi-Fi 段）的 IP；"
echo "                          · 或者干脆不用局域网 —— 让 WorkBuddy 把下载页发布成"
echo "                            一个公网链接，手机点链接就能下。"
echo "    A、B 都打不开       → 先看【2】：服务没在跑就 bash tools/serve-apk.sh。"
echo
