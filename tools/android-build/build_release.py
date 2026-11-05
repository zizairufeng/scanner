"""构建 release APK 并用自建 keystore 签名，产出可直接安装的成品。

AGP 在没有 signingConfig 时产出的是 app-release-unsigned.apk（不能安装），
所以这里走「构建 -> zipalign -> apksigner」的手工签名链路，避免把密码写进
build.gradle.kts（那会把密钥信息留在交付代码里）。
"""
import os
import shutil
import subprocess
import sys
import time

BASE = r"C:\Users\czk\androidbuild"
SDK = os.path.join(BASE, "sdk")
BT = os.path.join(SDK, "build-tools", "35.0.0")
JDK = os.path.join(BASE, "jdk", "jdk17")
GRADLE = os.path.join(BASE, "gradle", "gradle-8.9", "bin", "gradle.bat")
GRADLE_USER_HOME = os.path.join(BASE, "gradle-home")
PROJECT = r"C:\Users\czk\WorkBuddy\2026-09-17-15-46-21\ScanStock"
LOG_DIR = os.path.join(BASE, "logs")
OUT_DIR = os.path.join(BASE, "out")
KS = os.path.join(BASE, "release.keystore")
KS_PASS = "scanstock"
KS_ALIAS = "scanstock"

os.makedirs(LOG_DIR, exist_ok=True)
os.makedirs(OUT_DIR, exist_ok=True)

env = os.environ.copy()
env["JAVA_HOME"] = JDK
env["ANDROID_HOME"] = SDK
env["ANDROID_SDK_ROOT"] = SDK
env["GRADLE_USER_HOME"] = GRADLE_USER_HOME
env["PATH"] = os.path.join(JDK, "bin") + os.pathsep + env.get("PATH", "")
env["TERM"] = "dumb"


def run(args, label, log_name=None, cwd=PROJECT):
    print("=" * 74, flush=True)
    print(label, flush=True)
    print("=" * 74, flush=True)
    print("$ " + " ".join(args), flush=True)

    logf = open(os.path.join(LOG_DIR, log_name), "w", encoding="utf-8",
                errors="replace") if log_name else None
    t0 = time.time()
    p = subprocess.Popen(["cmd", "/c"] + args, env=env, cwd=cwd,
                         stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                         text=True, encoding="utf-8", errors="replace", bufsize=1)
    tail = []
    for line in p.stdout:
        if logf:
            logf.write(line)
            logf.flush()
        s = line.rstrip()
        if s:
            tail.append(s)
            if len(tail) > 500:
                tail.pop(0)
            if not log_name:
                print(s, flush=True)
    p.wait()
    if logf:
        logf.close()
        # 构建日志太长，只回显关键行
        for s in tail:
            if any(k in s for k in ("FAILURE", "error:", "e: ", "Execution failed",
                                    "Unresolved", "> Task", "BUILD", "warning:",
                                    "Caused by", "What went wrong")):
                print(s, flush=True)
    print(f"\n-> exit={p.returncode}  用时 {time.time()-t0:.1f}s", flush=True)
    return p.returncode, tail


# ---------- 1. 构建 release ----------
rc, tail = run([GRADLE, "--no-daemon", "--console=plain", "--stacktrace",
                ":app:assembleRelease"],
               "【1】构建 release（开启 R8 混淆 + 资源压缩，耗时较长）",
               "release.log")
if rc != 0:
    print("\n!! release 构建失败，错误摘要：")
    for s in tail[-80:]:
        print("  " + s)
    sys.exit(1)

unsigned = os.path.join(PROJECT, "app", "build", "outputs", "apk", "release",
                        "app-release-unsigned.apk")
# 若 AGP 直接产出已签名文件（配了 signingConfig 时），兜底识别
signed_by_agp = os.path.join(PROJECT, "app", "build", "outputs", "apk", "release",
                             "app-release.apk")

src = unsigned if os.path.exists(unsigned) else signed_by_agp
if not os.path.exists(src):
    print(f"!! 找不到 release 产物：\n   {unsigned}\n   {signed_by_agp}")
    sys.exit(1)
print(f"\n  构建产物: {src}  {os.path.getsize(src)/1024/1024:.2f} MB")

aligned = os.path.join(OUT_DIR, "scanstock-release-aligned.apk")
final = os.path.join(OUT_DIR, "ScanStock-1.3-release.apk")

# ---------- 2. zipalign ----------
zipalign = os.path.join(BT, "zipalign.exe")
if os.path.exists(aligned):
    os.remove(aligned)
rc, _ = run([zipalign, "-p", "-f", "4", src, aligned], "【2】zipalign 4 字节对齐")
if rc != 0:
    print("!! zipalign 失败")
    sys.exit(1)

# ---------- 3. apksigner 签名 ----------
apksigner = os.path.join(BT, "apksigner.bat")
if os.path.exists(final):
    os.remove(final)
rc, _ = run([apksigner, "sign",
             "--ks", KS,
             "--ks-key-alias", KS_ALIAS,
             "--ks-pass", f"pass:{KS_PASS}",
             "--key-pass", f"pass:{KS_PASS}",
             "--v1-signing-enabled", "true",
             "--v2-signing-enabled", "true",
             "--v3-signing-enabled", "true",
             "--out", final, aligned],
            "【3】apksigner 签名（v1+v2+v3，兼容 Android 7 至最新）")
if rc != 0:
    print("!! 签名失败")
    sys.exit(1)

# ---------- 4. 验证 ----------
rc, tail = run([apksigner, "verify", "--verbose", "--print-certs", final],
               "【4】签名校验")
print()
if os.path.exists(final):
    print(f"  最终 release APK: {final}")
    print(f"  大小: {os.path.getsize(final)/1024/1024:.2f} MB")
    print("\nRELEASE_OK")
    sys.exit(0)
print("!! 未产出最终文件")
sys.exit(1)
