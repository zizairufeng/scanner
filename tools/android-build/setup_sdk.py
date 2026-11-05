"""用 sdkmanager 安装构建所需的 Android SDK 组件。

只装三样：platform-tools / platforms;android-35 / build-tools;35.0.0，
不用 --install 全量，避免拉一堆用不到的东西。
"""
import os
import subprocess
import sys

BASE = r"C:\Users\czk\androidbuild"
SDK = os.path.join(BASE, "sdk")
JDK = os.path.join(BASE, "jdk", "jdk17")
SDKMGR = os.path.join(SDK, "cmdline-tools", "latest", "bin", "sdkmanager.bat")

if not os.path.exists(SDKMGR):
    print(f"!! 找不到 sdkmanager: {SDKMGR}")
    sys.exit(1)

env = os.environ.copy()
env["JAVA_HOME"] = JDK
env["PATH"] = os.path.join(JDK, "bin") + os.pathsep + env.get("PATH", "")
env["ANDROID_HOME"] = SDK
env["ANDROID_SDK_ROOT"] = SDK

PACKAGES = [
    "platform-tools",
    "platforms;android-35",
    "build-tools;35.0.0",
]


def run(args, label):
    print("=" * 70)
    print(label)
    print("=" * 70)
    print("$ " + " ".join(args))
    p = subprocess.run(
        ["cmd", "/c"] + args,
        env=env,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
    )
    out = (p.stdout or "") + (p.stderr or "")
    # sdkmanager 会刷大量回车式进度条，压掉纯进度行让日志可读
    keep = []
    for line in out.splitlines():
        s = line.strip()
        if not s:
            continue
        if s.startswith("[") and "%" in s:
            continue
        keep.append(s)
    print("\n".join(keep[-60:]))
    print(f"-> exit={p.returncode}")
    return p.returncode


# 先自检：列一下已安装包，确认 sdkmanager 本身能跑起来
rc = run([SDKMGR, f"--sdk_root={SDK}", "--list_installed"], "【0】sdkmanager 自检")
if rc != 0:
    print("\n!! sdkmanager 无法运行，后续步骤无法继续")
    sys.exit(1)

rc = run(
    [SDKMGR, f"--sdk_root={SDK}", "--install"] + PACKAGES,
    "【1】安装 platform-tools / platforms;android-35 / build-tools;35.0.0",
)

print()
print("=" * 70)
print("安装结果检查")
print("=" * 70)
checks = [
    ("adb.exe", os.path.join(SDK, "platform-tools", "adb.exe")),
    ("android.jar (API35)", os.path.join(SDK, "platforms", "android-35", "android.jar")),
    ("aapt2.exe", os.path.join(SDK, "build-tools", "35.0.0", "aapt2.exe")),
    ("d8.bat", os.path.join(SDK, "build-tools", "35.0.0", "d8.bat")),
    ("apksigner.bat", os.path.join(SDK, "build-tools", "35.0.0", "apksigner.bat")),
    ("zipalign.exe", os.path.join(SDK, "build-tools", "35.0.0", "zipalign.exe")),
    ("core-lambda-stubs.jar", os.path.join(SDK, "build-tools", "35.0.0",
                                           "core-lambda-stubs.jar")),
]

# aapt2 在部分版本里是 aapt2.exe，另一些是 aapt2（无扩展名），两种都认
aapt2_nix = os.path.join(SDK, "build-tools", "35.0.0", "aapt2")
all_ok = True
for label, path in checks:
    exists = os.path.exists(path)
    if not exists and label == "aapt2.exe" and os.path.exists(aapt2_nix):
        path, exists = aapt2_nix, True
    print(f"  {'OK  ' if exists else 'MISS'} {label:24s} {path}")
    if not exists:
        all_ok = False

print()
print("SDK_SETUP_OK" if all_ok else "SDK_SETUP_INCOMPLETE")
sys.exit(0 if all_ok else 1)
