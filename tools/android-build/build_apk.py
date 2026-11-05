"""驱动 Gradle 构建 ScanStock，产出 debug APK。

分三步：
  1. 自检：gradle --version，确认 JDK/Gradle 版本配对
  2. 写 local.properties（指向本机 SDK）
  3. :app:assembleDebug

日志实时落盘到 C:/Users/czk/androidbuild/logs/，同时回显。
"""
import os
import subprocess
import sys
import time

BASE = r"C:\Users\czk\androidbuild"
SDK = os.path.join(BASE, "sdk")
JDK = os.path.join(BASE, "jdk", "jdk17")
GRADLE_USER_HOME = os.path.join(BASE, "gradle-home")
GRADLE = os.path.join(BASE, "gradle", "gradle-8.9", "bin", "gradle.bat")
PROJECT = r"C:\Users\czk\WorkBuddy\2026-09-17-15-46-21\ScanStock"
LOG_DIR = os.path.join(BASE, "logs")
os.makedirs(LOG_DIR, exist_ok=True)


def make_env():
    env = os.environ.copy()
    env["JAVA_HOME"] = JDK
    env["ANDROID_HOME"] = SDK
    env["ANDROID_SDK_ROOT"] = SDK
    env["GRADLE_USER_HOME"] = GRADLE_USER_HOME
    env["PATH"] = os.path.join(JDK, "bin") + os.pathsep + env.get("PATH", "")
    # 关掉 Gradle 的富文本控制台，日志才干净
    env["TERM"] = "dumb"
    return env


def run(args, label, log_name, timeout=None):
    print("=" * 74, flush=True)
    print(label, flush=True)
    print("=" * 74, flush=True)
    print("$ " + " ".join(args), flush=True)

    log_path = os.path.join(LOG_DIR, log_name)
    t0 = time.time()
    lines_tail = []

    with open(log_path, "w", encoding="utf-8", errors="replace") as logf:
        p = subprocess.Popen(
            ["cmd", "/c"] + args,
            env=make_env(),
            cwd=PROJECT,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
            text=True,
            encoding="utf-8",
            errors="replace",
            bufsize=1,
        )
        for line in p.stdout:
            logf.write(line)
            logf.flush()
            s = line.rstrip()
            if s:
                lines_tail.append(s)
                if len(lines_tail) > 400:
                    lines_tail.pop(0)
                print(s, flush=True)
        p.wait(timeout=timeout)

    dt = time.time() - t0
    print(f"\n-> exit={p.returncode}  用时 {dt:.1f}s  日志: {log_path}", flush=True)
    return p.returncode, lines_tail, log_path


def main():
    step = sys.argv[1] if len(sys.argv) > 1 else "all"

    # ---------- 步骤 1：版本自检 ----------
    if step in ("all", "version"):
        rc, _, _ = run([GRADLE, "--version"], "【1】Gradle / JVM 版本自检", "version.log")
        if rc != 0:
            print("!! gradle --version 失败，终止")
            return 1

    # ---------- 步骤 2：写 local.properties ----------
    if step in ("all", "props"):
        props_path = os.path.join(PROJECT, "local.properties")
        content = (
            "## 本文件由构建脚本生成：指向本机 Android SDK 位置。\n"
            "## Android Studio 打开工程时会自动覆盖为它自己的 SDK 路径。\n"
            "sdk.dir=" + SDK.replace("\\", "\\\\").replace(":", "\\:") + "\n"
        )
        with open(props_path, "w", encoding="utf-8") as f:
            f.write(content)
        print("=" * 74)
        print("【2】写入 local.properties")
        print("=" * 74)
        print(f"  {props_path}")
        print("  " + content.strip().splitlines()[-1])

    # ---------- 步骤 3：构建 ----------
    if step in ("all", "build"):
        args = [
            GRADLE,
            "--no-daemon",
            "--console=plain",
            "--stacktrace",
            ":app:assembleDebug",
        ]
        rc, tail, log_path = run(args, "【3】构建 debug APK（首次会下载依赖，耗时较长）",
                                 "build.log")

        if rc != 0:
            print("\n" + "!" * 74)
            print("构建失败，下面是从日志里提取的编译错误行：")
            print("!" * 74)
            errs = [l for l in tail
                    if ("e: " in l or "error:" in l or "FAILURE" in l
                        or "Execution failed" in l or "Caused by" in l
                        or "Unresolved reference" in l or "Unresolved" in l)]
            for l in errs[-60:]:
                print("  " + l)
            return 1

        # ---------- 步骤 4：定位产物 ----------
        apk = os.path.join(PROJECT, "app", "build", "outputs", "apk", "debug",
                           "app-debug.apk")
        print("\n" + "=" * 74)
        print("【4】产物检查")
        print("=" * 74)
        if os.path.exists(apk):
            size = os.path.getsize(apk)
            print(f"  找到 APK: {apk}")
            print(f"  大小: {size/1024/1024:.2f} MB ({size} 字节)")
            print("\nBUILD_OK")
            return 0
        print(f"  !! 构建返回 0 但没找到 {apk}")
        return 1

    return 0


if __name__ == "__main__":
    sys.exit(main())
