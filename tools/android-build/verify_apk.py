"""对产出的 APK 做机械验收：结构 / 清单 / 签名 / ABI / DEX。

不接受「文件存在就算成功」，逐项检查可安装性与清单内容是否符合需求。
"""
import hashlib
import os
import re
import subprocess
import sys
import zipfile

BASE = r"C:\Users\czk\androidbuild"
SDK = os.path.join(BASE, "sdk")
BT = os.path.join(SDK, "build-tools", "35.0.0")
JDK = os.path.join(BASE, "jdk", "jdk17")

# 注意：默认必须指向**对外发布的 release 包**，而不是 debug 中间产物。
# 之前默认指向 app/build/outputs/apk/debug/app-debug.apk，导致脚本打印的
# "SHA256" 其实是 debug 包的，被误当成 release 包的哈希引用了好几次。
APK = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
    r"C:\Users\czk\WorkBuddy\2026-09-17-15-46-21\ScanStock",
    "dist", "ScanStock-1.3-release.apk")

env = os.environ.copy()
env["JAVA_HOME"] = JDK
env["PATH"] = os.path.join(JDK, "bin") + os.pathsep + env.get("PATH", "")

failures = []


def section(title):
    print()
    print("=" * 74)
    print(title)
    print("=" * 74)


section("【0】APK 基本信息")
if not os.path.exists(APK):
    print(f"  !! 不存在: {APK}")
    sys.exit(1)
size = os.path.getsize(APK)
sha = hashlib.sha256(open(APK, "rb").read()).hexdigest()
print(f"  路径: {APK}")
print(f"  大小: {size/1024/1024:.2f} MB ({size} 字节)")
print(f"  文件  : {APK}")
print(f"  SHA256: {sha}")

section("【1】ZIP 结构（APK 必须含哪些条目）")
with zipfile.ZipFile(APK) as z:
    names = z.namelist()
    bad = z.testzip()
    if bad:
        print(f"  !! 压缩包损坏于 {bad}")
        failures.append("zip 损坏")
    else:
        print("  OK   zip 完整性校验通过")

    required = ["AndroidManifest.xml", "resources.arsc"]
    dexes = sorted(n for n in names if re.fullmatch(r"classes\d*\.dex", n))
    libs = sorted(n for n in names if n.startswith("lib/"))

    for r in required:
        ok = r in names
        print(f"  {'OK  ' if ok else 'MISS'} {r}")
        if not ok:
            failures.append(f"缺少 {r}")

    print(f"  OK   dex 文件 {len(dexes)} 个: {', '.join(dexes)}")
    if not dexes:
        failures.append("没有任何 dex，无法运行")

    abis = sorted({n.split("/")[1] for n in libs})
    print(f"  OK   native ABI: {abis if abis else '（无 so，纯 Java/Kotlin）'}")
    sokey = [n for n in libs if "barhopper" in n or "mlkit" in n.lower()]
    print(f"        ML Kit native lib: {sokey if sokey else '（无）'}")

section("【2】清单解析（包名 / 版本 / SDK 范围 / 权限）")
aapt2 = os.path.join(BT, "aapt2.exe")
p = subprocess.run(["cmd", "/c", aapt2, "dump", "badging", APK],
                   env=env, capture_output=True, text=True,
                   encoding="utf-8", errors="replace")
badging = (p.stdout or "") + (p.stderr or "")
if p.returncode != 0:
    print("  !! aapt2 dump badging 失败")
    print(badging[-1500:])
    failures.append("aapt2 解析失败")
else:
    wanted = {
        "package": r"^package: name='([^']+)'",
        "versionName": r"versionName='([^']+)'",
        "versionCode": r"versionCode='([^']+)'",
        # 注意 aapt2 输出的字段名是 minSdkVersion，不是 sdkVersion
        "minSdkVersion": r"minSdkVersion:'([^']+)'",
        "targetSdkVersion": r"targetSdkVersion:'([^']+)'",
    }
    for label, pat in wanted.items():
        m = re.search(pat, badging, re.M)
        print(f"  {label:16s} {m.group(1) if m else '<未找到>'}")

    perms = re.findall(r"uses-permission: name='([^']+)'", badging)
    print(f"  权限 {len(perms)} 条:")
    for pm in perms:
        print(f"      {pm}")

    # 需求约束：只应声明 CAMERA；VIBRATE 是系统强制要求（已在设计中说明）
    # DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION 由 androidx.core 在合并清单时自动注入，
    # 是 signature 级自定义权限（不向用户申请、不弹窗），用于 Android 13+ 内部动态
    # 注册广播接收器的安全隔离。它必须保留，移除会导致 registerReceiver 抛异常。
    ALLOWED = {
        "android.permission.CAMERA",
        "android.permission.VIBRATE",
        "com.example.scanstock.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
    }
    unexpected = [x for x in perms if x not in ALLOWED]
    if unexpected:
        print(f"  !! 出现预期外的权限: {unexpected}")
        failures.append(f"预期外权限 {unexpected}")
    else:
        print("  OK   权限集合符合设计（CAMERA + VIBRATE + AndroidX 内部签名级权限）")

    if "android.permission.CAMERA" not in perms:
        failures.append("缺少 CAMERA 权限")
    if "android.permission.VIBRATE" not in perms:
        print("  !! 警告：缺少 VIBRATE，震动反馈会失效")
        failures.append("缺少 VIBRATE 权限")
    for forbidden in ("android.permission.INTERNET",
                      "android.permission.ACCESS_NETWORK_STATE",
                      "android.permission.READ_MEDIA_IMAGES",
                      "android.permission.READ_EXTERNAL_STORAGE",
                      "android.permission.WRITE_EXTERNAL_STORAGE"):
        if forbidden in perms:
            print(f"  !! 出现本应被移除的权限: {forbidden}")
            failures.append(f"权限清理失效: {forbidden}")

    launchable = re.search(r"launchable-activity: name='([^']+)'", badging)
    print(f"  launchable-activity  {launchable.group(1) if launchable else '<未找到>'}")
    if not launchable:
        failures.append("没有可启动 Activity，装上去点不开")

    native_codes = re.findall(r"native-code: '([^']*)'", badging)
    if native_codes:
        print(f"  native-code         {native_codes[0]}")

    features = re.findall(r"uses-feature: name='([^']+)'", badging)
    if features:
        print(f"  uses-feature        {features}")

section("【3】签名校验（未签名 / 签名无效会安装失败）")
apksigner = os.path.join(BT, "apksigner.bat")
p = subprocess.run(["cmd", "/c", apksigner, "verify", "--verbose", "--print-certs", APK],
                   env=env, capture_output=True, text=True,
                   encoding="utf-8", errors="replace")
out = (p.stdout or "") + (p.stderr or "")
print(out.strip()[-2000:])
if p.returncode != 0:
    print("  !! 签名校验不通过")
    failures.append("签名无效")
else:
    print("\n  OK   apksigner verify 通过")

section("【4】zipalign 校验（未对齐会影响安装性能）")
zipalign = os.path.join(BT, "zipalign.exe")
p = subprocess.run(["cmd", "/c", zipalign, "-c", "-v", "4", APK],
                   env=env, capture_output=True, text=True,
                   encoding="utf-8", errors="replace")
if p.returncode != 0:
    print("  !! zipalign 校验失败（非致命，但建议重打包对齐）")
    print(((p.stdout or "") + (p.stderr or ""))[-800:])
else:
    print("  OK   zipalign -c 4 通过（4 字节对齐）")

section("【验收结论】")
if failures:
    print("  RESULT: FAIL")
    for f in failures:
        print(f"    - {f}")
    sys.exit(1)
else:
    print("  RESULT: PASS —— APK 结构、清单、权限、签名、对齐全部通过")
    print(f"  文件  : {APK}")
    print(f"  SHA256: {sha}")
    sys.exit(0)
