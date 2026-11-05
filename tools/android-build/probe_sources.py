"""为 Android 构建环境挑选最快的下载源。

只读前若干字节计时，不落盘，用来在正式下载前做路由决策。
"""
import ssl
import time
import urllib.request

ctx = ssl.create_default_context()
ctx.check_hostname = False
ctx.verify_mode = ssl.CERT_NONE

UA = {
    "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                  "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
}

SAMPLE = 512 * 1024  # 每个源只读 512KB 来测速


def timed(name, url, note=""):
    t0 = time.time()
    try:
        req = urllib.request.Request(url, headers=UA)
        with urllib.request.urlopen(req, timeout=20, context=ctx) as r:
            got = 0
            while got < SAMPLE:
                chunk = r.read(65536)
                if not chunk:
                    break
                got += len(chunk)
            dt = time.time() - t0
            # 最终 URL 是判断是否被重定向到境外 CDN 的关键信息
            final = r.url
            speed = got / dt / 1024 / 1024 if dt > 0 else 0
            print(f"  {name:34s} OK   {got:>7d}B  {dt:5.2f}s  {speed:6.2f}MB/s")
            if final != url:
                print(f"  {'':34s} -> 重定向到 {final[:110]}")
            return True, speed
    except Exception as e:
        print(f"  {name:34s} FAIL {type(e).__name__}: {str(e)[:80]}")
        return False, 0.0


print("=" * 78)
print("【1】JDK 下载源测速（目标：JDK 17 / Windows x64 / zip）")
print("=" * 78)
jdk_candidates = [
    ("Microsoft OpenJDK(aka.ms)",
     "https://aka.ms/download-jdk/microsoft-jdk-17.0.13-windows-x64.zip"),
    ("Adoptium API(latest17)",
     "https://api.adoptium.net/v3/binary/latest/17/ga/windows/x64/jdk/hotspot/normal/eclipse"),
    ("HuaweiCloud OpenJDK",
     "https://mirrors.huaweicloud.com/openjdk/17.0.2/openjdk-17.0.2_windows-x64_bin.zip"),
    ("TUNA Adoptium",
     "https://mirrors.tuna.tsinghua.edu.cn/Adoptium/17/jdk/x64/windows/"
     "OpenJDK17U-jdk_x64_windows_hotspot_17.0.13_11.zip"),
]
jdk_ok = []
for n, u in jdk_candidates:
    ok, sp = timed(n, u)
    if ok:
        jdk_ok.append((sp, n, u))

print()
print("=" * 78)
print("【2】Gradle 8.9 发行版测速")
print("=" * 78)
gradle_candidates = [
    ("TencentCloud mirror",
     "https://mirrors.cloud.tencent.com/gradle/gradle-8.9-bin.zip"),
    ("Gradle official",
     "https://services.gradle.org/distributions/gradle-8.9-bin.zip"),
]
gradle_ok = []
for n, u in gradle_candidates:
    ok, sp = timed(n, u)
    if ok:
        gradle_ok.append((sp, n, u))

print()
print("=" * 78)
print("【3】Android SDK 组件源测速")
print("=" * 78)
sdk_candidates = [
    ("Google dl (cmdline-tools)",
     "https://dl.google.com/android/repository/commandlinetools-win-11076708_latest.zip"),
    ("Google dl (build-tools 35)",
     "https://dl.google.com/android/repository/build-tools_r35_windows.zip"),
    ("Google dl (platform 35)",
     "https://dl.google.com/android/repository/platform-35_r01.zip"),
]
sdk_ok = []
for n, u in sdk_candidates:
    ok, sp = timed(n, u)
    if ok:
        sdk_ok.append((sp, n, u))

print()
print("=" * 78)
print("【4】Maven 仓库可达性（只取 pom，几千字节）")
print("=" * 78)
maven_candidates = [
    ("google() maven2 - MLKit",
     "https://dl.google.com/dl/android/maven2/com/google/mlkit/"
     "barcode-scanning/17.3.0/barcode-scanning-17.3.0.pom"),
    ("google() maven2 - AGP",
     "https://dl.google.com/dl/android/maven2/com/android/tools/build/"
     "gradle/8.7.3/gradle-8.7.3.pom"),
    ("repo1.maven.org - junit",
     "https://repo1.maven.org/maven2/junit/junit/4.13.2/junit-4.13.2.pom"),
    ("aliyun google mirror - MLKit",
     "https://maven.aliyun.com/repository/google/com/google/mlkit/"
     "barcode-scanning/17.3.0/barcode-scanning-17.3.0.pom"),
    ("aliyun central mirror - junit",
     "https://maven.aliyun.com/repository/central/junit/junit/4.13.2/junit-4.13.2.pom"),
    ("TencentCloud maven - junit",
     "https://mirrors.cloud.tencent.com/nexus/repository/maven-public/junit/"
     "junit/4.13.2/junit-4.13.2.pom"),
]
for n, u in maven_candidates:
    timed(n, u)

print()
print("=" * 78)
print("【结论】")
print("=" * 78)
if jdk_ok:
    jdk_ok.sort(reverse=True)
    print(f"  最快 JDK 源：{jdk_ok[0][1]}  {jdk_ok[0][0]:.2f} MB/s")
    print(f"    URL = {jdk_ok[0][2]}")
else:
    print("  !! 没有任何 JDK 源可用")
if gradle_ok:
    gradle_ok.sort(reverse=True)
    print(f"  最快 Gradle 源：{gradle_ok[0][1]}  {gradle_ok[0][0]:.2f} MB/s")
    print(f"    URL = {gradle_ok[0][2]}")
else:
    print("  !! 没有任何 Gradle 源可用")
if sdk_ok:
    sdk_ok.sort(reverse=True)
    print(f"  最快 SDK 源：{sdk_ok[0][1]}  {sdk_ok[0][0]:.2f} MB/s")
else:
    print("  !! 没有任何 SDK 源可用")
