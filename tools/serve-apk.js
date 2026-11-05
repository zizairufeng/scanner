/**
 * ScanStock 内网下载服务（纯 Node 标准库，零第三方依赖）
 *
 * 用途：让同一局域网内的手机直接下载 dist/ 目录下的 APK，不用数据线、不用外网。
 *
 * 用法：
 *   node tools/serve-apk.js                 # 默认监听**本机所有外部网卡**的 8080 端口
 *   node tools/serve-apk.js --port 9000     # 换端口
 *   node tools/serve-apk.js --host 192.168.2.150        # 只监听指定地址（可重复、可逗号分隔）
 *   node tools/serve-apk.js --host 0.0.0.0              # 监听所有网卡（含回环）
 *   node tools/serve-apk.js --open          # 额外在服务目录里生成一个 index.html 供浏览器打开
 *
 * 安全约束：
 *   1. 只提供 dist/ 里匹配 `ScanStock-*.apk` 的文件（启动时扫描成白名单），
 *      不做目录遍历、不列目录，任何未知路径一律 404；
 *   2. 只绑定本机**当前实际存在的外部 IPv4 地址**（不含回环、不含 APIPA）。
 *      要收窄暴露面就用 --host 显式指定一个地址；
 *   3. 只读文件，不开放任何写入接口。
 *
 * ## 为什么是「多地址」而不是「挑一个」
 *
 * 一个 `http.Server` 一次只能 `listen` 一个 host，所以早先的实现是
 * `lans[0]` 挑一个绑——**挑错就等于整条服务白起**。本机装了 Tailscale 之后
 * 有两块外部网卡，`os.networkInterfaces()` 的顺序又不保证，实测踩过两次：
 *
 *   1. 挑中 Tailscale（`100.85.243.81`）→ 手机连同一个 Wi-Fi **打不开**，
 *      而服务在跑、日志正常、端口也在监听，属于最难查的那类故障；
 *   2. 挑中以太网（`192.168.2.150`）→ 手机在**外面**的时候又下不了，
 *      哪怕它其实已经装了 Tailscale 并加入同一个 tailnet。
 *
 * 这两条通路各有各的用处（局域网快；Tailscale 任意网络可达），
 * **没有理由二选一**。所以改成：为每个外部地址各起一个 listener，
 * 同一端口、同一份内容。任一条通就行，一条绑不上（比如 Tailscale 刚退出、
 * DHCP 还没发地址）也只跳过它，不影响另一条。
 *
 * 支持断点续传（HTTP Range），手机浏览器/下载管理器中断后可续传。
 */

'use strict';

const http = require('http');
const fs = require('fs');
const fsp = require('fs/promises');
const path = require('path');
const os = require('os');
const crypto = require('crypto');

// ────────────────────────── 参数解析 ──────────────────────────

function parseArgs(argv) {
  // hosts 是数组：不给就绑「全部外部地址」，给了就只绑指定的那几个。
  // 支持 --host a --host b 和 --host a,b 两种写法（前者更符合习惯，后者打字少）。
  const args = { port: 8080, hosts: [], open: false };
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--port' || a === '-p') args.port = Number(argv[++i]);
    else if (a === '--host' || a === '-h') {
      const v = argv[++i];
      if (!v || v.startsWith('--')) {
        console.error('--host 后面要跟一个地址，例如 --host 192.168.2.150');
        process.exit(1);
      }
      args.hosts.push(...v.split(',').map((s) => s.trim()).filter(Boolean));
    } else if (a === '--open') args.open = true;
    else if (a === '--help') {
      console.log(
        '用法: node tools/serve-apk.js [--port 8080] [--host 192.168.x.x|0.0.0.0]... [--open]\n' +
          '      不传 --host 时监听本机所有外部网卡'
      );
      process.exit(0);
    }
  }
  if (!Number.isInteger(args.port) || args.port < 1 || args.port > 65535) {
    console.error('端口非法：' + args.port);
    process.exit(1);
  }
  return args;
}

// ────────────────────────── 工具 ──────────────────────────

/**
 * 取本机的内网 IPv4 地址，**按「适合手机访问」的程度排序**。
 *
 * ## 为什么不能只取第一个
 *
 * 本机同时装过 Tailscale，于是 `os.networkInterfaces()` 会同时给出
 * `100.85.243.81`（Tailscale 的 CGNAT 地址）和 `192.168.2.150`（真实以太网）。
 * 接口顺序不保证，`lans[0]` 就可能落到 Tailscale 上——服务照样起来、
 * 日志照样打印，但**手机连同一个 Wi-Fi 却访问不到**（最坏的那种失败：
 * 看起来一切正常）。
 *
 * 排序依据：
 *   0. `192.168.x.x` —— 家用 / 办公室路由器最标准的网段，手机一定同网段
 *   1. `10.x.x.x`    —— 企业内网
 *   2. `172.16-31.x` —— 私有网段第三段
 *   3. 其他公网 / 未知地址
 *   4. `100.64-127.x` —— **CGNAT，Tailscale 用的就是这个段**，排最后
 *      （手机不装 Tailscale 就连不上，只在没有别的地址时才用它兜底）
 */
function addressRank(ip) {
  if (ip.startsWith('192.168.')) return 0;
  if (ip.startsWith('10.')) return 1;
  const m = /^172\.(\d+)\./.exec(ip);
  if (m) {
    const n = Number(m[1]);
    if (n >= 16 && n <= 31) return 2;
  }
  const c = /^100\.(\d+)\./.exec(ip);
  if (c) {
    const n = Number(c[1]);
    if (n >= 64 && n <= 127) return 4;   // CGNAT / Tailscale
  }
  return 3;
}

function lanAddresses() {
  const out = [];
  for (const list of Object.values(os.networkInterfaces())) {
    for (const ni of list || []) {
      if (ni.family !== 'IPv4' || ni.internal) continue;
      if (ni.address.startsWith('169.254.')) continue; // APIPA，没有 DHCP 时的假地址
      out.push(ni.address);
    }
  }
  // 稳定排序：同档内保持接口原顺序；同一地址被两块网卡同时报出来时只留一个
  // （曾经出现过 IPv6/IPv4 双栈适配器把同一地址报两遍，绑第二次必然 EADDRINUSE）
  const seen = new Set();
  return out
    .map((ip, i) => ({ ip, i }))
    .sort((a, b) => addressRank(a.ip) - addressRank(b.ip) || a.i - b.i)
    .map((x) => x.ip)
    .filter((ip) => (seen.has(ip) ? false : seen.add(ip)));
}

/**
 * 把地址归类到「哪条通路」，用于启动日志分组和状态告警。
 *
 * 只用于**展示和判断**，不参与绑定决策——绑定决策永远由 [lanAddresses] 的实际枚举结果说了算。
 */
function addressKind(ip) {
  if (ip === '0.0.0.0' || ip === '::') return 'all';
  if (ip.startsWith('127.')) return 'loopback';
  if (ip.startsWith('192.168.') || ip.startsWith('10.')) return 'lan';
  const m = /^172\.(\d+)\./.exec(ip);
  if (m && Number(m[1]) >= 16 && Number(m[1]) <= 31) return 'lan';
  const c = /^100\.(\d+)\./.exec(ip);
  if (c && Number(c[1]) >= 64 && Number(c[1]) <= 127) return 'tailscale'; // CGNAT
  return 'other';
}

/** 每个通路在日志里怎么说人话 */
const KIND_LABEL = {
  lan: { title: '同一 Wi-Fi 的手机', hint: '手机连的是家里/办公室的 Wi-Fi' },
  tailscale: { title: 'Tailscale 设备', hint: '装了 Tailscale 并加入同一 tailnet，任意网络都能下' },
  other: { title: '其它地址', hint: '' },
  all: { title: '所有网卡', hint: '含回环，暴露面最大' },
  loopback: { title: '仅本机自测（手机打不开这个地址）', hint: '在电脑自己的浏览器里开，用来确认服务是否正常' }
};

/**
 * 决定这一次要绑哪些地址。
 *
 * - 显式 `--host`：听用户的，绑错了是用户的选择（但会校验格式）；
 * - 否则：**本机所有外部地址**，按 [addressRank] 排序（局域网在前，Tailscale 在后）。
 *
 * 一个地址都没有（网线没插、Wi-Fi 没连）才退回回环，让本机至少能自测。
 */
function resolveTargets(args, lans) {
  if (args.hosts.length > 0) {
    const bad = args.hosts.filter((h) => !/^\d{1,3}(\.\d{1,3}){3}$/.test(h) && h !== 'localhost');
    if (bad.length > 0) {
      console.error(`--host 不是合法 IPv4 地址：${bad.join(', ')}`);
      process.exit(1);
    }
    return [...new Set(args.hosts)];
  }
  // 默认：所有外部地址 + **回环**。
  //
  // 为什么把回环也绑上（v1.6 改）：用户想确认「服务到底通不通」时，第一反应是在
  // 电脑本机的浏览器里打开 http://127.0.0.1:8080/ —— 只绑外部地址的话这一步必然失败，
  // 而它会被直接误判成「服务没起来」，于是去查一堆无关的东西。
  // 绑上回环后本机自测立刻可通；报告里回环单独成组、标注「仅本机自测」，
  // 不会让人误以为手机也能用这个地址。
  return lans.length > 0 ? [...lans, '127.0.0.1'] : ['127.0.0.1'];
}

function humanSize(bytes) {
  const mb = bytes / (1024 * 1024);
  return mb >= 1 ? mb.toFixed(2) + ' MB' : (bytes / 1024).toFixed(1) + ' KB';
}

function esc(s) {
  return String(s).replace(/[&<>"']/g, (c) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
  })[c]);
}

// ────────────────────────── 文件白名单 ──────────────────────────

const ROOT = path.resolve(__dirname, '..', 'dist');

/**
 * 启动时扫描 dist/，把符合 `ScanStock-*.apk` 的文件收进白名单。
 * 由 [main] 在确认 ROOT 存在之后调用，所以这里是 `let` 而不是 `const`。
 *
 * ## 为什么不再写死文件名（v1.6 改）
 *
 * 之前这里硬编码了 `ScanStock-1.5.0-release.apk` 两条。后果是：出了 1.6.0 的包之后，
 * 服务**照样能起来、日志照样正常、下载也照样成功**——只是下到的是上一版。
 * 这是最难查的一类故障（一切"正常"，只有版本是错的），而且每次发版都要记得改代码。
 *
 * 改成按模式发现：dist/ 里有哪些 ScanStock-*.apk 就提供哪些，`/` 指向 release（没有则最新）。
 *
 * 安全属性**不变**：仍然只认 dist/ **这一层**目录下的 `ScanStock-*.apk`，
 * 不做目录遍历、不列目录、未知路径一律 404；`statFile` 里还有一道
 * 「解析后的父目录必须等于 ROOT」的兜底。
 */
function buildFileMap() {
  const names = fs.readdirSync(ROOT)
    .filter((n) => /^ScanStock-[^/\\]+\.apk$/.test(n))
    .sort();

  const map = new Map();
  if (names.length === 0) return map;

  // 首页给「推荐安装」的那个：优先 release（R8 混淆、体积最小），
  // 没有 release 就退到排序后的最后一个（版本号最大的那个）
  const releases = names.filter((n) => /-release\.apk$/.test(n));
  const preferred = releases.length > 0 ? releases[releases.length - 1] : names[names.length - 1];
  map.set('/', { file: preferred });
  for (const name of names) map.set('/' + name, { file: name });
  return map;
}

/** URL 路径 → 文件。启动时填充一次 */
let FILES = new Map();

async function statFile(name) {
  const full = path.join(ROOT, name);
  // 双重保险：解析后的路径必须仍在 dist 目录内（防目录穿越）
  if (path.dirname(path.resolve(full)) !== path.resolve(ROOT)) return null;
  const st = await fsp.stat(full).catch(() => null);
  return st && st.isFile() ? { full, size: st.size, mtime: st.mtimeMs } : null;
}

function sha256File(full) {
  return new Promise((resolve, reject) => {
    const h = crypto.createHash('sha256');
    fs.createReadStream(full)
      .on('data', (d) => h.update(d))
      .on('end', () => resolve(h.digest('hex')))
      .on('error', reject);
  });
}

// ────────────────────────── 页面 ──────────────────────────

function indexHtml(entries, baseUrl, shaOf) {
  const rows = entries
    .map((e) => {
      const url = baseUrl + '/' + encodeURIComponent(e.name);
      return `      <div class="card">
        <div class="title">${esc(e.name)}</div>
        <div class="meta">${humanSize(e.size)} · SHA256 <code>${esc(shaOf.get(e.name))}</code></div>
        <a class="btn" href="${url}">下载</a>
        <div class="raw">${esc(url)}</div>
      </div>`;
    })
    .join('\n');

  return `<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>ScanStock APK 内网下载</title>
<style>
  body{margin:0;padding:24px;font:15px/1.6 system-ui,-apple-system,"Microsoft YaHei",sans-serif;
       background:#0f1115;color:#e6e8ec}
  h1{font-size:19px;margin:0 0 4px}
  .sub{color:#9aa3b2;font-size:13px;margin-bottom:20px}
  .card{background:#171a21;border:1px solid #262b36;border-radius:12px;padding:14px;margin-bottom:12px}
  .title{font-weight:600;word-break:break-all}
  .meta{color:#9aa3b2;font-size:12px;margin:6px 0 10px;word-break:break-all}
  code{background:#0f1115;padding:1px 5px;border-radius:4px;font-size:11px}
  .btn{display:inline-block;background:#3b82f6;color:#fff;text-decoration:none;
       padding:8px 18px;border-radius:8px;font-weight:600}
  .raw{margin-top:8px;color:#7d8797;font-size:11px;word-break:break-all}
  .tip{margin-top:18px;color:#9aa3b2;font-size:12px;border-left:3px solid #3b82f6;padding-left:10px}
</style>
</head>
<body>
  <h1>ScanStock APK 内网下载</h1>
  <div class="sub">同一 Wi-Fi 下用手机浏览器打开本页即可下载。装之前需要在手机上允许「未知来源应用」。</div>
${rows}
  <div class="tip">
    SHA256 用于在手机上核对文件完整性：<br>
    <code>certutil -hashfile 文件名 SHA256</code>（Windows）或 <code>sha256sum 文件名</code>（Termux）。
  </div>
</body>
</html>`;
}

// ────────────────────────── 服务 ──────────────────────────

async function main() {
  const args = parseArgs(process.argv.slice(2));

  if (!fs.existsSync(ROOT)) {
    console.error('找不到目录：' + ROOT);
    process.exit(1);
  }

  // 白名单在这里才填充：放到模块顶层会在 ROOT 不存在时直接抛异常，
  // 而那句异常是「ENOENT: no such file or directory, scandir ...」，远不如上面那行清楚
  FILES = buildFileMap();

  // 启动时先把白名单文件的元信息与 SHA256 算好（文件不大，一次性算完避免每次请求重复 IO）
  const entries = [];
  const shaOf = new Map();
  for (const name of new Set([...FILES.values()].map((v) => v.file))) {
    const st = await statFile(name);
    if (!st) continue;
    const sha = await sha256File(st.full);
    shaOf.set(name, sha);
    entries.push({ name, size: st.size });
  }
  if (entries.length === 0) {
    console.error('dist 目录里没有可提供的 APK');
    process.exit(1);
  }

  // 提成具名函数而不是内联箭头函数：多地址要起多个 http.Server，
  // 它们共用同一个请求处理逻辑。写成内联的话要么复制一份、要么闭包套娃。
  function handleRequest(req, res) {
    const urlPath = decodeURIComponent((req.url || '/').split('?')[0]);
    const entry = FILES.get(urlPath);

    // 未知路径一律 404（不列目录、不支持任何遍历）
    if (!entry) {
      res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
      res.end('404 Not Found\n');
      return;
    }

    // 「/」是目录首页：返回 HTML 索引页
    if (urlPath === '/') {
      const host = (req.headers.host || '').split(':')[0];
      const port = (req.headers.host || '').split(':')[1] || String(args.port);
      const base = `http://${host}${port ? ':' + port : ''}`;
      const html = indexHtml(entries, base, shaOf);
      res.writeHead(200, {
        'Content-Type': 'text/html; charset=utf-8',
        'Content-Length': Buffer.byteLength(html)
      });
      res.end(req.method === 'HEAD' ? undefined : html);
      console.log(
        `[${new Date().toLocaleTimeString('zh-CN')}] ${req.socket.remoteAddress} 访问首页`
      );
      return;
    }

    const st = statFileSyncCache[entry.file];
    if (!st) {
      res.writeHead(404, { 'Content-Type': 'text/plain; charset=utf-8' });
      res.end('404 Not Found\n');
      return;
    }

    const headers = {
      'Content-Type': 'application/vnd.android.package-archive',
      'Content-Disposition': `attachment; filename="${entry.file}"`,
      'Accept-Ranges': 'bytes',
      'Cache-Control': 'no-store'
    };

    // HEAD：只回头部（有些下载器会先探一次）
    if (req.method === 'HEAD') {
      res.writeHead(200, { ...headers, 'Content-Length': st.size });
      res.end();
      return;
    }
    if (req.method !== 'GET') {
      res.writeHead(405, { 'Content-Type': 'text/plain; charset=utf-8', Allow: 'GET, HEAD' });
      res.end('405 Method Not Allowed\n');
      return;
    }

    // Range 断点续传：只支持单段 bytes=start-end，多段直接忽略（回全量）
    const range = req.headers.range;
    let start = 0;
    let end = st.size - 1;
    let isRange = false;
    if (range && /^bytes=(\d*)-(\d*)$/.test(range.trim())) {
      const m = /^bytes=(\d*)-(\d*)$/.exec(range.trim());
      if (m[1] !== '' || m[2] !== '') {
        start = m[1] === '' ? Math.max(0, st.size - Number(m[2])) : Number(m[1]);
        end = m[2] === '' ? st.size - 1 : Math.min(Number(m[2]), st.size - 1);
        if (start > end || start >= st.size) {
          res.writeHead(416, {
            'Content-Type': 'text/plain; charset=utf-8',
            'Content-Range': `bytes */${st.size}`
          });
          res.end('416 Range Not Satisfiable\n');
          return;
        }
        isRange = true;
      }
    }

    const length = end - start + 1;
    res.writeHead(isRange ? 206 : 200, {
      ...headers,
      'Content-Length': length,
      ...(isRange ? { 'Content-Range': `bytes ${start}-${end}/${st.size}` } : {})
    });

    const stream = fs.createReadStream(st.full, { start, end });
    let sent = 0;
    stream.on('data', (c) => (sent += c.length));
    stream.on('end', () => {
      console.log(
        `[${new Date().toLocaleTimeString('zh-CN')}] ${req.socket.remoteAddress} ` +
          `下载 ${entry.file} ${isRange ? '(续传)' : ''} ${humanSize(sent)}`
      );
    });
    stream.on('error', () => res.destroy());
    stream.pipe(res);
  }

  // statFileSyncCache：启动时已 stat 过，这里复用结果（同步版，避免每次请求 await）
  // 注意：放在 createServer 之后填充，回调运行时已就绪
  const statFileSyncCache = Object.create(null);
  // 注意：shaOf 是 Map，必须用 [...shaOf.keys()]；Object.keys(Map) 永远返回空数组
  for (const name of [...shaOf.keys()]) {
    // eslint-disable-next-line no-await-in-loop
    const st = await statFile(name);
    if (st) statFileSyncCache[name] = { full: st.full, size: st.size };
  }

  // ────────────────────────── 起服务 ──────────────────────────

  const lans = lanAddresses();
  const targets = resolveTargets(args, lans);

  const servers = [];
  const settled = []; // 每个地址的绑定结果，{ host, ok, err? }

  function report() {
    const bound = settled.filter((s) => s.ok).map((s) => s.host);
    console.log('');
    console.log('  ScanStock 内网下载服务已启动');
    console.log('  ────────────────────────────────────────');
    console.log(`  提供目录   : ${ROOT}`);
    for (const e of entries) {
      console.log(`  文件       : ${e.name}  ${humanSize(e.size)}`);
      console.log(`  SHA256     : ${shaOf.get(e.name)}`);
    }
    console.log('  ────────────────────────────────────────');
    console.log(`  监听 ${bound.length} 个地址（同一份内容、同一个端口），任一条通即可：`);
    console.log('');

    // 按通路分组。同一 Wi-Fi 和 Tailscale 是两种完全不同的使用场景，
    // 混在一行里列出来，用户还得自己猜哪个地址对应哪种情况。
    const groups = new Map();
    for (const ip of bound) {
      const k = addressKind(ip);
      if (!groups.has(k)) groups.set(k, []);
      groups.get(k).push(ip);
    }
    for (const [kind, ips] of groups) {
      const label = KIND_LABEL[kind] || KIND_LABEL.other;
      console.log(`  ${label.title}${label.hint ? '（' + label.hint + '）' : ''}`);
      for (const ip of ips) console.log(`    http://${ip}:${args.port}/`);
      console.log('');
    }

    const fails = settled.filter((s) => !s.ok);
    if (fails.length > 0) {
      console.log('  以下地址绑定失败（不影响上面这些）：');
      for (const f of fails) {
        console.log(`    ${f.host}  ${f.err.code || ''} ${f.err.message}`.trimEnd());
      }
      console.log('');
    }

    console.log('  ────────────────────────────────────────');
    // 这几条是手机连不上时的排查顺序，按「实际遇到频率」排。
    // 写在这里而不是只写在文档里：服务一启动就能看到，不用再去翻 README。
    console.log('  手机打不开时按顺序查这几条：');
    console.log('    1. 本窗口是否还开着 —— 关掉这个窗口/按了 Ctrl+C，服务就停了');
    console.log('    2. 手机和电脑是否连的**同一个** Wi-Fi（电脑当前在上面那几个地址上）');
    console.log('    3. 路由器是否开了「AP 隔离 / 客户端隔离」（开了设备之间互相看不见）');
    console.log('    4. 电脑防火墙是否放行该端口：sudo ufw allow ' + args.port + '/tcp');
    console.log('    5. 地址要照抄上面那一行（含 http:// 和端口号），别只敲 IP');
    console.log('');
    console.log('  按 Ctrl+C 停止');
    console.log('');
  }

  function maybeReport() {
    if (settled.length < targets.length) return;
    if (!settled.some((s) => s.ok)) {
      console.error('服务启动失败：没有一个地址能绑定。');
      for (const f of settled) {
        console.error(`  ${f.host}  ${f.err.code || ''} ${f.err.message}`.trimEnd());
      }
      process.exit(1);
    }
    report();
  }

  for (const host of targets) {
    const srv = http.createServer(handleRequest);

    srv.on('error', (err) => {
      // 单个地址绑不上**不该拖垮整条服务**：Tailscale 可能刚退出、网卡可能还没拿到
      // DHCP 地址、某个 IP 也可能被别的进程单独占了。只要还有一条通路活着就有用。
      settled.push({ host, ok: false, err });
      maybeReport();
    });

    srv.listen(args.port, host, () => {
      settled.push({ host, ok: true });
      maybeReport();
    });

    servers.push(srv);
  }

  function shutdown() {
    console.log('\n正在停止服务…');
    let n = servers.length;
    if (n === 0) return process.exit(0);
    for (const s of servers) {
      s.close(() => {
        if (--n === 0) process.exit(0);
      });
    }
    // 兜底：有 keep-alive 连接卡着时 close 回调可能迟迟不来，2 秒后强退
    setTimeout(() => process.exit(0), 2000).unref();
  }

  process.on('SIGINT', shutdown);
  process.on('SIGTERM', shutdown);
}

main().catch((e) => {
  console.error('启动异常：', e);
  process.exit(1);
});
