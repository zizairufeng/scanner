# -*- coding: utf-8 -*-
"""
R8 混淆安全性专项核查（针对 native 层硬编码的类名）。

背景：
    从 libbarhopper_v3.so 里提取出 4 个硬编码类描述符——
        Lcom/google/android/libraries/barhopper/MultiScaleDecodingOptions;
        Lcom/google/android/libraries/barhopper/MultiScaleDetectionOptions;
        Lcom/google/android/libraries/barhopper/OnedRecognitionOptions;
        Ljava/lang/String;
    native 库是按「字符串类名」查找这些类的，一旦 R8 改名，JNI 必崩。
    本项目 proguard-rules.pro 并没有 keep 这个包，所以必须确认
    「是官方 consumer rules 兜住了」，还是「真的漏了」。

本脚本做三件事：
    1. 解出所有 ML Kit / CameraX AAR 自带的 consumer 混淆规则（proguard.txt），看官方 keep 了什么；
    2. 把「native 层硬编码的类名」和「manifest 里按字符串引用的类名」当作 MUST-KEEP 清单，
       到 release dex 里逐个点名；
    3. 给出结论：release 包是否存在真实的 R8 反射崩溃风险。
"""
import os
import re
import struct
import sys
import zipfile

CACHE = r"C:\Users\czk\androidbuild\gradle-home\caches\modules-2\files-2.1"
APK_REL = r"C:\Users\czk\WorkBuddy\2026-09-17-15-46-21\ScanStock\dist\ScanStock-1.3-release.apk"

# ── 从 native 库和 manifest 提取的「字符串引用」类名，必须保留原名 ──
MUST_KEEP_FROM_STRINGS = {
    # 来自 libbarhopper_v3.so 的硬编码类描述符
    "com.google.android.libraries.barhopper.MultiScaleDecodingOptions":
        "libbarhopper_v3.so 硬编码，JNI 按类名查找",
    "com.google.android.libraries.barhopper.MultiScaleDetectionOptions":
        "libbarhopper_v3.so 硬编码，JNI 按类名查找",
    "com.google.android.libraries.barhopper.OnedRecognitionOptions":
        "libbarhopper_v3.so 硬编码，JNI 按类名查找",
    # 来自 AndroidManifest.xml 的 android:name 字符串
    "com.example.scanstock.MainActivity":
        "manifest android:name 字符串",
    "androidx.camera.camera2.Camera2Config$DefaultProvider":
        "CameraX manifest meta-data 值，反射 newInstance",
    "androidx.camera.core.impl.MetadataHolderService":
        "CameraX manifest 声明的 Service 类名",
    "androidx.camera.core.impl.MetadataHolderService$1":
        "CameraX Service 内部类",
}


# ══════════════════════════════════════════════════════════════════
#  dex 解析（与 deep_verify.py 同一实现）
# ══════════════════════════════════════════════════════════════════

def _uleb128(buf, off):
    result, shift = 0, 0
    while True:
        b = buf[off]
        off += 1
        result |= (b & 0x7F) << shift
        if not (b & 0x80):
            break
        shift += 7
    return result, off


def parse_dex_classes(buf):
    if buf[:4] != b"dex\n":
        raise ValueError("不是合法 dex")
    string_ids_size = struct.unpack_from("<I", buf, 56)[0]
    string_ids_off = struct.unpack_from("<I", buf, 60)[0]
    type_ids_size = struct.unpack_from("<I", buf, 64)[0]
    type_ids_off = struct.unpack_from("<I", buf, 68)[0]

    def get_string(idx):
        if idx < 0 or idx >= string_ids_size:
            return None
        off = struct.unpack_from("<I", buf, string_ids_off + idx * 4)[0]
        _, p = _uleb128(buf, off)
        end = buf.index(b"\x00", p)
        return buf[p:end].decode("utf-8", "replace")

    classes = set()
    for i in range(type_ids_size):
        str_idx = struct.unpack_from("<I", buf, type_ids_off + i * 4)[0]
        d = get_string(str_idx)
        if d and d.startswith("L") and d.endswith(";"):
            classes.add(d[1:-1].replace("/", "."))
    return classes


def apk_classes(apk):
    classes = set()
    with zipfile.ZipFile(apk) as z:
        for n in sorted(x for x in z.namelist() if re.match(r"classes\d*\.dex$", x)):
            classes |= parse_dex_classes(z.read(n))
    return classes


# ══════════════════════════════════════════════════════════════════

def find_aars():
    out = []
    for group in ("com.google.mlkit", "androidx.camera", "com.google.android.gms"):
        root = os.path.join(CACHE, group)
        if not os.path.isdir(root):
            continue
        for dirpath, _, files in os.walk(root):
            for f in files:
                if f.endswith(".aar"):
                    out.append(os.path.join(dirpath, f))
    return sorted(out)


def main():
    print("=" * 78)
    print("R8 混淆安全性专项核查 —— native 层硬编码类名的生存性")
    print("=" * 78)
    print()

    # ── 第 1 步：官方 consumer 混淆规则 ──
    print("── 1. 各 AAR 自带的 consumer 混淆规则（proguard.txt） ──")
    aars = find_aars()
    print(f"  找到 {len(aars)} 个 AAR")
    print()
    keep_lines = {}
    for aar in aars:
        try:
            with zipfile.ZipFile(aar) as z:
                if "proguard.txt" not in z.namelist():
                    continue
                txt = z.read("proguard.txt").decode("utf-8", "replace")
        except Exception:
            continue
        if not txt.strip():
            continue
        label = os.path.basename(aar).replace(".aar", "")
        rules = [l.strip() for l in txt.splitlines()
                 if l.strip() and not l.strip().startswith("#")]
        keep_lines[label] = rules
        print(f"  ┌─ {label}  ({len(rules)} 条规则)")
        for r in rules:
            print(f"  │   {r}")
        print(f"  └─")
        print()

    # 汇总官方 keep 的包前缀
    official_prefixes = set()
    for rules in keep_lines.values():
        for r in rules:
            for m in re.finditer(r"-keep(?:class|names)?\s+(?:class\s+)?([\w.$]+)", r):
                official_prefixes.add(m.group(1))
    print(f"  官方 consumer rules 中出现的 keep 目标（{len(official_prefixes)} 个）:")
    for p in sorted(official_prefixes):
        print(f"    {p}")
    print()

    # ── 第 2 步：MUST-KEEP 清单逐个点名 ──
    print("── 2. 字符串引用的类名在 release dex 中是否原样存在 ──")
    classes = apk_classes(APK_REL)
    print(f"  release dex 共 {len(classes)} 个类")
    print()
    failures = []
    for fqcn, why in sorted(MUST_KEEP_FROM_STRINGS.items()):
        if fqcn in classes:
            print(f"  OK   {fqcn}")
            print(f"       （{why}）")
        else:
            print(f"  MISS {fqcn}")
            print(f"       → {why}  <<< 运行时崩溃风险")
            failures.append(f"{fqcn}  ({why})")
    print()

    # ── 第 3 步：barhopper 包整体情况 ──
    print("── 3. com.google.android.libraries.barhopper 包在 release 中的存续情况 ──")
    bh = sorted(c for c in classes if c.startswith("com.google.android.libraries.barhopper"))
    if bh:
        print(f"  共 {len(bh)} 个类:")
        for c in bh:
            print(f"    {c}")
    else:
        print("  该包在 release dex 中不存在任何类")
        failures.append("com.google.android.libraries.barhopper 整包缺失")
    print()

    # ── 第 4 步：三个 native 引用类是否被改名（模糊查法） ──
    print("── 4. 兜底：是否存在『疑似被 R8 改名后』的残留（同名不同包） ──")
    for short in ("MultiScaleDecodingOptions", "MultiScaleDetectionOptions",
                  "OnedRecognitionOptions"):
        hits = sorted(c for c in classes if c.endswith("." + short))
        if hits:
            print(f"  {short}:")
            for h in hits:
                print(f"    {h}")
        else:
            print(f"  {short}: 完全不存在（类被删除或改名到无法识别）")
            failures.append(f"{short} 在 release 中查无此类")
    print()

    print("=" * 78)
    if failures:
        print(f"核查结果：{len(failures)} 处风险")
        for f in failures:
            print(f"  !! {f}")
        print("R8_STRINGS_FAIL")
    else:
        print("核查结果：通过")
        print("  所有 native 层 / manifest 层按字符串引用的类名，在 R8 混淆后均原样保留。")
        print("R8_STRINGS_OK")
    print("=" * 78)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
