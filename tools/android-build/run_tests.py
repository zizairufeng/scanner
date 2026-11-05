"""运行 JVM 单元测试，并解析 JUnit XML 报告给出精确的通过/失败统计。

此前这批测试只是「写好了但从未执行」，本脚本让它真正跑起来。
"""
import glob
import os
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

BASE = r"C:\Users\czk\androidbuild"
SDK = os.path.join(BASE, "sdk")
JDK = os.path.join(BASE, "jdk", "jdk17")
GRADLE_USER_HOME = os.path.join(BASE, "gradle-home")
GRADLE = os.path.join(BASE, "gradle", "gradle-8.9", "bin", "gradle.bat")
PROJECT = r"C:\Users\czk\WorkBuddy\2026-09-17-15-46-21\ScanStock"
LOG_DIR = os.path.join(BASE, "logs")
os.makedirs(LOG_DIR, exist_ok=True)

env = os.environ.copy()
env["JAVA_HOME"] = JDK
env["ANDROID_HOME"] = SDK
env["ANDROID_SDK_ROOT"] = SDK
env["GRADLE_USER_HOME"] = GRADLE_USER_HOME
env["PATH"] = os.path.join(JDK, "bin") + os.pathsep + env.get("PATH", "")
env["TERM"] = "dumb"

args = [GRADLE, "--no-daemon", "--console=plain", ":app:testDebugUnitTest"]

print("=" * 74)
print("运行 JVM 单元测试：:app:testDebugUnitTest")
print("=" * 74)
print("$ " + " ".join(args))

t0 = time.time()
log_path = os.path.join(LOG_DIR, "test.log")
with open(log_path, "w", encoding="utf-8", errors="replace") as logf:
    p = subprocess.Popen(["cmd", "/c"] + args, env=env, cwd=PROJECT,
                         stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                         text=True, encoding="utf-8", errors="replace", bufsize=1)
    tail = []
    for line in p.stdout:
        logf.write(line)
        logf.flush()
        s = line.rstrip()
        if s:
            tail.append(s)
            if len(tail) > 600:
                tail.pop(0)
            print(s, flush=True)
    p.wait()
dt = time.time() - t0

print(f"\n-> gradle exit={p.returncode}  用时 {dt:.1f}s  日志: {log_path}")

# ---------- 关键：Gradle 失败时必须立刻失败，绝不能拿上一轮的 XML 冒充通过 ----------
# 血泪教训：Gradle 配置阶段报错（例如 build.gradle.kts 里的 AGP API 用错）时，
# 测试任务根本不会执行，build/test-results 里却还留着上一次成功的 XML。
# 若不检查 exit code，脚本会把「旧的 44 个用例」当成这一轮的结果输出 TESTS_OK，
# 看起来一切正常，实际上这一版代码压根没被编译过。
if p.returncode != 0:
    print()
    print("=" * 74)
    print("GRADLE_FAILED —— 测试未执行（下面是从日志里提取的错误）")
    print("=" * 74)
    for s in tail[-40:]:
        print("  " + s)
    sys.exit(1)

# ---------- 解析 JUnit XML 报告 ----------
results_dir = os.path.join(PROJECT, "app", "build", "test-results",
                           "testDebugUnitTest")
xmls = sorted(glob.glob(os.path.join(results_dir, "TEST-*.xml")))

# 同样的防呆：报告必须是本轮生成的（mtime 晚于本脚本启动时刻）
stale = [x for x in xmls if os.path.getmtime(x) < t0]
if stale:
    print("!! 发现上一轮遗留的测试报告，结果不可信：")
    for x in stale:
        print("   " + x)
    sys.exit(1)

print()
print("=" * 74)
print("测试报告解析（来源：build/test-results/testDebugUnitTest/TEST-*.xml）")
print("=" * 74)

if not xmls:
    print("  !! 没找到任何 XML 报告，测试可能根本没执行")
    for s in tail[-40:]:
        print("  " + s)
    sys.exit(1)

total = failures = errors = skipped = 0
all_cases = []
for x in xmls:
    root = ET.parse(x).getroot()
    suite = root.get("name", os.path.basename(x))
    t = int(root.get("tests") or 0)
    f = int(root.get("failures") or 0)
    e = int(root.get("errors") or 0)
    sk = int(root.get("skipped") or 0)
    tm = float(root.get("time") or 0)
    total += t
    failures += f
    errors += e
    skipped += sk

    status = "PASS" if (f == 0 and e == 0) else "FAIL"
    print(f"\n  [{status}] {suite.split('.')[-1]:22s} 用例 {t:2d}  "
          f"失败 {f}  错误 {e}  跳过 {sk}  耗时 {tm:.3f}s")

    for tc in root.findall("testcase"):
        name = tc.get("name", "")
        ok = True
        detail = ""
        for tag, label in (("failure", "失败"), ("error", "错误"),
                           ("skipped", "跳过")):
            el = tc.find(tag)
            if el is not None:
                ok = tag == "skipped"
                detail = (el.get("message") or "").strip().replace("\n", " ")[:180]
                if tag != "skipped":
                    print(f"        !! {label}: {name}")
                    print(f"           {detail}")
                break
        all_cases.append((suite, name, ok))

print()
print("=" * 74)
print("汇总")
print("=" * 74)
print(f"  测试套件 {len(xmls)} 个")
print(f"  用例总数 {total}")
print(f"  通过     {total - failures - errors - skipped}")
print(f"  失败     {failures}")
print(f"  错误     {errors}")
print(f"  跳过     {skipped}")
print(f"  总耗时   {dt:.1f}s（含 Gradle 启动与增量编译）")

print()
if failures == 0 and errors == 0 and total > 0:
    print(f"TESTS_OK  全部 {total} 个用例通过")
    sys.exit(0)
print("TESTS_FAILED")
sys.exit(1)
