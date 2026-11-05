"""并行下载 Android 构建环境所需的三个大件。

特性：
  - 断点续传（Range 请求），中断后重跑不会从头下
  - 单文件失败自动重试 3 次，指数退避
  - 每个文件一个线程，实时打印进度
  - 全部落到 C:/Users/czk/androidbuild/dl，不污染用户环境
"""
import os
import ssl
import sys
import threading
import time
import urllib.error
import urllib.request

DL_DIR = r"C:\Users\czk\androidbuild\dl"
os.makedirs(DL_DIR, exist_ok=True)

ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE

UA = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                  "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
}

TASKS = [
    {
        "name": "JDK17",
        "url": "https://mirrors.huaweicloud.com/openjdk/17.0.2/openjdk-17.0.2_windows-x64_bin.zip",
        "file": "openjdk-17.0.2_windows-x64_bin.zip",
        "min_mb": 150,
    },
    {
        "name": "Gradle8.9",
        "url": "https://mirrors.cloud.tencent.com/gradle/gradle-8.9-bin.zip",
        "file": "gradle-8.9-bin.zip",
        "min_mb": 100,
    },
    {
        "name": "CmdlineTools",
        "url": "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip",
        "file": "commandlinetools-win-11076708_latest.zip",
        "min_mb": 100,
    },
]

lock = threading.Lock()
progress_state = {}


def log(msg):
    with lock:
        print(msg, flush=True)


def remote_size(url):
    """拿远端文件大小，顺便确认源还活着。"""
    req = urllib.request.Request(url, headers=UA, method="HEAD")
    try:
        with urllib.request.urlopen(req, timeout=30, context=ctx) as r:
            return int(r.headers.get("Content-Length") or 0)
    except Exception:
        return 0


def download(task):
    name = task["name"]
    url = task["url"]
    dest = os.path.join(DL_DIR, task["file"])
    tmp = dest + ".part"
    min_bytes = task["min_mb"] * 1024 * 1024

    for attempt in range(1, 4):
        try:
            already = os.path.getsize(tmp) if os.path.exists(tmp) else 0
            headers = dict(UA)
            if already > 0:
                headers["Range"] = f"bytes={already}-"

            req = urllib.request.Request(url, headers=headers)
            with urllib.request.urlopen(req, timeout=60, context=ctx) as r:
                total = int(r.headers.get("Content-Length") or 0)
                if already > 0 and r.status != 206:
                    # 服务器不支持续传，从头来
                    already = 0
                    mode = "wb"
                else:
                    mode = "ab" if already > 0 else "wb"
                    if already > 0:
                        total += already

                got = already
                t0 = time.time()
                last_print = 0.0
                with open(tmp, mode) as f:
                    while True:
                        chunk = r.read(262144)
                        if not chunk:
                            break
                        f.write(chunk)
                        got += len(chunk)
                        now = time.time()
                        if now - last_print >= 2.0:
                            dt = now - t0
                            sp = (got - already) / dt / 1024 / 1024 if dt > 0 else 0
                            pct = (got / total * 100) if total else 0
                            log(f"  [{name}] {got/1024/1024:7.1f}/{total/1024/1024:.1f}MB "
                                f"({pct:5.1f}%)  {sp:5.2f}MB/s")
                            last_print = now
                size = os.path.getsize(tmp)
                t0_total = time.time() - t0
                sp_avg = (size - already) / t0_total / 1024 / 1024 if t0_total > 0 else 0

            if size < min_bytes:
                raise RuntimeError(f"文件偏小 {size} < {min_bytes}，疑似下载不完整")

            os.replace(tmp, dest)
            log(f"  [OK] {name} 完成 {size/1024/1024:.1f}MB  均速 {sp_avg:.2f}MB/s")
            return True

        except Exception as e:
            log(f"  [!!] {name} 第 {attempt} 次失败: {type(e).__name__}: {str(e)[:120]}")
            if attempt < 3:
                time.sleep(2 * attempt)

    log(f"  [XX] {name} 三次尝试均失败")
    return False


print("=" * 70)
print("开始并行下载 Android 构建工具链（3 线程）")
print(f"目标目录: {DL_DIR}")
print("=" * 70)

threads = []
results = {}


def worker(task):
    results[task["name"]] = download(task)


for t in TASKS:
    th = threading.Thread(target=worker, args=(t,), daemon=False)
    th.start()
    threads.append(th)

for th in threads:
    th.join()

print()
print("=" * 70)
print("下载结果汇总")
print("=" * 70)
all_ok = True
for t in TASKS:
    dest = os.path.join(DL_DIR, t["file"])
    if os.path.exists(dest):
        print(f"  OK   {t['name']:14s} {os.path.getsize(dest)/1024/1024:8.1f} MB  "
              f"{t['file']}")
    else:
        print(f"  FAIL {t['name']:14s} 缺失")
        all_ok = False

print()
print("ALL_DOWNLOADS_OK" if all_ok else "SOME_DOWNLOADS_FAILED")
sys.exit(0 if all_ok else 1)
