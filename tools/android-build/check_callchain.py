# -*- coding: utf-8 -*-
"""
用 release dex 的「方法引用表」验证功能链路是否完整。

为什么用这个判据：
    R8 会把「只有一个调用点的简单方法」内联进调用方，然后把原类整个删掉，
    并在 mapping.txt 里标注 `-> R8$$REMOVED$$CLASS$$N`。
    看到这个标记**不能**直接判定「功能没了」——很可能只是代码被内联优化了。

    真正权威的判据是：该方法**最终是否还在 dex 里被引用**。
    dex 的 method_ids 表列出了所有被引用的方法（含被内联后留下的调用），
    直接读它就能回答「这条调用链还在不在」。

本脚本检查：
    1. ML Kit 识别链路的关键方法是否仍被引用
    2. CameraX 预览链路的关键方法是否仍被引用
    3. 应用内被标记为 R8$$REMOVED$$CLASS$$ 的类，其原本提供的能力是否仍被调用
"""
import os
import re
import struct
import sys
import zipfile

APK_REL = r"C:\Users\czk\WorkBuddy\2026-09-17-15-46-21\ScanStock\dist\ScanStock-1.3-release.apk"
APK_DBG = r"C:\Users\czk\WorkBuddy\2026-09-17-15-46-21\ScanStock\dist\ScanStock-1.3-debug.apk"
MAPPING = (r"C:\Users\czk\WorkBuddy\2026-09-17-15-46-21\ScanStock"
           r"\app\build\outputs\mapping\release\mapping.txt")

# 这些前缀的类在 release 里会被 R8 改名（未被 keep），
# 因此按原名查不到，需要先经 mapping.txt 反查新名再验证。
RENAMED_PREFIXES = ("androidx.camera.", "androidx.activity.", "androidx.lifecycle.",
                    "androidx.core.", "android.os.")

CLASS_LINE = re.compile(r"^(?P<old>[\w.$]+)\s*->\s*(?P<new>[\w.$]+):\s*$")
MEMBER_LINE = re.compile(
    r"^\s+.*?\s(?P<name>[\w$<>]+)\((?P<args>[^)]*)\)(?::\d+)*\s*->\s*(?P<new>[\w$<>]+)\s*$")


def load_mapping(prefixes):
    """
    只解析关心的前缀，避免把 33MB mapping 全塞进内存。
    返回 (类映射 old->new, 方法映射 (oldClass, oldMethod) -> (newClass, newMethod),
          被 R8 移除的类集合)
    """
    cls_map, meth_map, removed = {}, {}, set()
    cur_old = cur_new = None
    with open(MAPPING, "r", encoding="utf-8", errors="replace") as f:
        for line in f:
            if not line.strip() or line.startswith("#"):
                continue
            if not line[0].isspace():
                m = CLASS_LINE.match(line.rstrip())
                if m:
                    cur_old, cur_new = m.group("old"), m.group("new")
                    if cur_old.startswith(prefixes):
                        cls_map[cur_old] = cur_new
                        if cur_new.startswith("R8$$REMOVED$$CLASS$$"):
                            removed.add(cur_old)
                    else:
                        cur_old = cur_new = None
                continue
            if cur_old is None:
                continue
            m = MEMBER_LINE.match(line.rstrip())
            if m:
                meth_map[(cur_old, m.group("name"))] = (cur_new, m.group("new"))
    return cls_map, meth_map, removed


def resolve_missing(fq_method, rel_methods, meth_map, removed):
    """
    对按原名查不到的方法，尝试用 mapping 反查它改名后的样子。
    返回 (状态, 说明)：
        ('RENAMED_OK', 新全名)   改名后仍被引用 => 调用链完整
        ('CLASS_REMOVED', '')    类被 R8 整体移除
        ('INLINED_OR_GONE', '')  名字不在映射表，可能被内联或确实丢了
    """
    cls, meth = fq_method.rsplit(".", 1)
    if cls in removed:
        return "CLASS_REMOVED", ""
    pair = meth_map.get((cls, meth))
    if pair:
        new_fq = f"{pair[0]}.{pair[1]}"
        if new_fq in rel_methods:
            return "RENAMED_OK", new_fq
        return "RENAMED_MISSING", new_fq
    # 名字不在映射表：可能是被 R8 内联进调用方了（那样功能仍在，只是无独立方法引用）
    return "INLINED_OR_GONE", ""

# 必须仍被引用的方法（类名, 方法名, 它支撑的功能）
REQUIRED_CALLS = [
    # ── ML Kit：扫码主链路 ──
    ("com.google.mlkit.vision.barcode.BarcodeScanning", "getClient",
     "创建条码扫描器"),
    ("com.google.mlkit.vision.barcode.BarcodeScannerOptions$Builder", "setBarcodeFormats",
     "显式开启 7 种码制"),
    ("com.google.mlkit.vision.barcode.BarcodeScannerOptions$Builder", "build",
     "构建扫描器配置"),
    ("com.google.mlkit.vision.barcode.BarcodeScannerOptions$Builder", "enableAllPotentialBarcodes",
     "开启『疑似条码』提示"),
    ("com.google.mlkit.vision.common.InputImage", "fromMediaImage",
     "相机帧 → InputImage"),
    ("com.google.mlkit.vision.common.InputImage", "fromBitmap",
     "相册图片 → InputImage"),
    ("com.google.mlkit.vision.barcode.BarcodeScanner", "process",
     "执行识别"),
    ("com.google.mlkit.vision.barcode.common.Barcode", "getRawValue",
     "取条码内容"),
    ("com.google.mlkit.vision.barcode.common.Barcode", "getFormat",
     "取码制类型"),
    ("com.google.mlkit.vision.barcode.common.Barcode", "getBoundingBox",
     "取条码位置（画取景提示）"),

    # ── CameraX：预览与分析绑定 ──
    ("androidx.camera.lifecycle.ProcessCameraProvider", "getInstance",
     "获取相机 Provider"),
    ("androidx.camera.lifecycle.ProcessCameraProvider", "bindToLifecycle",
     "绑定预览+分析到生命周期"),
    ("androidx.camera.core.Preview", "setSurfaceProvider",
     "把预览接到 PreviewView"),
    ("androidx.camera.core.ImageAnalysis", "setAnalyzer",
     "挂载图像分析器"),
    ("androidx.camera.core.ImageAnalysis", "setAnalyzeStrategy",
     "设置 KEEP_ONLY_LATEST 背压策略"),
    ("androidx.camera.core.CameraControl", "enableTorch",
     "闪光灯开关"),
    ("androidx.camera.core.CameraControl", "setZoomRatio",
     "双击缩放"),
    ("androidx.camera.core.Camera", "getCameraControl",
     "拿到相机控制句柄"),

    # ── 反馈：震动与提示音 ──
    ("android.os.Vibrator", "vibrate", "震动反馈"),
    ("android.media.ToneGenerator", "startTone", "提示音"),

    # ── 相册：Photo Picker（证明 ImageDecoder 的能力仍在）──
    ("androidx.activity.result.ActivityResultLauncher", "launch",
     "启动相册选择器（ImageDecoder 服务的入口）"),
]

# 若这些在 dex 中仍被引用，说明对应能力存续
CAPABILITY_KEYWORDS = [
    ("mlkit.vision.barcode", "ML Kit 条码识别"),
    ("camera.lifecycle", "CameraX 生命周期绑定"),
    ("camera.core", "CameraX 核心"),
    ("camera.view", "CameraX 预览控件"),
]


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


def parse_dex(buf):
    """
    返回 (类名集合, 方法全名集合)。
    方法全名形如 "com.foo.Bar.methodName"（只到类+方法名，不含签名，足够做链路判定）。
    """
    # dex header 布局（勿凭记忆，逐项核对过官方格式文档）：
    #   56 string_ids_size   60 string_ids_off
    #   64 type_ids_size     68 type_ids_off
    #   72 proto_ids_size    76 proto_ids_off
    #   80 field_ids_size    84 field_ids_off
    #   88 method_ids_size   92 method_ids_off      <-- 早期版本曾误写成 80/84（读成了字段表）
    #   96 class_defs_size  100 class_defs_off
    file_size = struct.unpack_from("<I", buf, 32)[0]
    string_ids_size = struct.unpack_from("<I", buf, 56)[0]
    string_ids_off = struct.unpack_from("<I", buf, 60)[0]
    type_ids_size = struct.unpack_from("<I", buf, 64)[0]
    type_ids_off = struct.unpack_from("<I", buf, 68)[0]
    field_ids_size = struct.unpack_from("<I", buf, 80)[0]
    method_ids_size = struct.unpack_from("<I", buf, 88)[0]
    method_ids_off = struct.unpack_from("<I", buf, 92)[0]

    def get_string(idx):
        if idx < 0 or idx >= string_ids_size:
            return None
        off = struct.unpack_from("<I", buf, string_ids_off + idx * 4)[0]
        _, p = _uleb128(buf, off)
        end = buf.index(b"\x00", p)
        return buf[p:end].decode("utf-8", "replace")

    # type_ids -> 类描述符
    types = []
    for i in range(type_ids_size):
        si = struct.unpack_from("<I", buf, type_ids_off + i * 4)[0]
        d = get_string(si)
        types.append(d[1:-1].replace("/", ".") if d and d.startswith("L") and d.endswith(";")
                     else (d or ""))

    classes = set(t for t in types if t)
    methods = set()
    # 边界自检：表不能越出文件范围，否则说明 header 偏移读错了
    if method_ids_off + method_ids_size * 8 > file_size:
        raise ValueError(
            f"method_ids 表越界: off={method_ids_off} size={method_ids_size} "
            f"file_size={file_size} —— header 偏移解析有误")
    for i in range(method_ids_size):
        off = method_ids_off + i * 8
        cls_idx = struct.unpack_from("<H", buf, off)[0]
        name_idx = struct.unpack_from("<I", buf, off + 4)[0]
        cls = types[cls_idx] if cls_idx < len(types) else ""
        name = get_string(name_idx) or ""
        if cls and name:
            methods.add(f"{cls}.{name}")

    return classes, methods, {"method_ids_size": method_ids_size,
                              "field_ids_size": field_ids_size,
                              "type_ids_size": type_ids_size,
                              "string_ids_size": string_ids_size}


def scan(apk):
    classes, methods, stats = set(), set(), {}
    with zipfile.ZipFile(apk) as z:
        for n in sorted(x for x in z.namelist() if re.match(r"classes\d*\.dex$", x)):
            c, m, s = parse_dex(z.read(n))
            classes |= c
            methods |= m
            for k, v in s.items():
                stats[k] = stats.get(k, 0) + v
    return classes, methods, stats


# 解析器自检：这些方法在任何真实 App 的 dex 中几乎必然存在。
# 若它们查不到，说明是「解析器读错了表」，而不是「APK 缺功能」。
SELF_CHECK_METHODS = [
    "java.lang.Object.<init>",
    "java.lang.String.<init>",
]


def self_check(methods):
    print("── 0. 解析器自检（防止『工具 bug』伪装成『APK 缺陷』） ──")
    ok = True
    for fq in SELF_CHECK_METHODS:
        if fq in methods:
            print(f"  OK   {fq}")
        else:
            print(f"  FAIL {fq}  <-- 解析器读错表了，下列结果全部不可信")
            ok = False
    print()
    return ok


def main():
    print("=" * 78)
    print("release dex 方法引用表验证 —— 功能链路完整性")
    print("=" * 78)
    print(f"release: {os.path.basename(APK_REL)}")
    print()

    rel_classes, rel_methods, rel_stats = scan(APK_REL)
    dbg_classes, dbg_methods, dbg_stats = scan(APK_DBG)
    print(f"  release: {len(rel_classes)} 类 / {len(rel_methods)} 个方法引用"
          f"  (method_ids={rel_stats.get('method_ids_size')}"
          f" field_ids={rel_stats.get('field_ids_size')}"
          f" type_ids={rel_stats.get('type_ids_size')})")
    print(f"  debug  : {len(dbg_classes)} 类 / {len(dbg_methods)} 个方法引用"
          f"  (method_ids={dbg_stats.get('method_ids_size')}"
          f" field_ids={dbg_stats.get('field_ids_size')}"
          f" type_ids={dbg_stats.get('type_ids_size')})")
    print()

    if not self_check(rel_methods):
        print("解析器自检未通过，终止（避免输出误导性结论）")
        return 2

    failures = []

    # ── 加载 mapping，用于对「被改名的类」做反查 ──
    cls_map, meth_map, removed = load_mapping(RENAMED_PREFIXES)
    print("── 0.5 mapping 反查表已加载 ──")
    print(f"  关心前缀的类映射 {len(cls_map)} 条 / 方法映射 {len(meth_map)} 条")
    print(f"  其中被 R8 整体移除的类: {len(removed)} 个")
    for c in sorted(removed)[:8]:
        print(f"    {c}")
    if len(removed) > 8:
        print(f"    ... 另有 {len(removed)-8} 个")
    print()

    def check(fq, label):
        """检查一个方法调用；若因 R8 改名而查不到，则经 mapping 反查后复查。"""
        if fq in rel_methods:
            print(f"  OK           {fq}")
            print(f"               {label}")
            return True
        status, detail = resolve_missing(fq, rel_methods, meth_map, removed)
        if status == "RENAMED_OK":
            print(f"  OK(改名后)    {fq}")
            print(f"               -> {detail}")
            print(f"               {label} ｜ R8 改了类名，代码引用同步更新，安全")
            return True
        if status == "RENAMED_MISSING":
            print(f"  BROKEN       {fq}")
            print(f"               映射到 {detail}，但该方法在 dex 中查不到  <-- {label}")
            return False
        if status == "CLASS_REMOVED":
            print(f"  REMOVED      {fq}")
            print(f"               宿主类被 R8 整体移除  <-- {label}")
            return False
        print(f"  MISSING      {fq}   <-- {label}")
        print(f"               名字不在映射表，可能已被内联进调用方")
        return False

    # ── 1. 关键调用链 ──
    print("── 1. 关键方法调用链是否仍在 release dex 中被引用 ──")
    for cls, meth, label in REQUIRED_CALLS:
        if not check(f"{cls}.{meth}", label):
            failures.append(f"调用链中断: {cls}.{meth}（{label}）")
    print()

    # ── 2. 能力包的关键词命中 ──
    print("── 2. 各能力包在方法引用表中的命中量 ──")
    for kw, label in CAPABILITY_KEYWORDS:
        rel_hits = sum(1 for m in rel_methods if kw in m)
        dbg_hits = sum(1 for m in dbg_methods if kw in m)
        if rel_hits:
            print(f"  OK   {label:22s} release={rel_hits}  debug={dbg_hits}")
        elif dbg_hits:
            # release 命中 0 但 debug 有 —— 说明该包整体被 R8 改名（正常）
            print(f"  REN  {label:22s} release=0  debug={dbg_hits}")
            print(f"       该包全部被 R8 改名（引用为代码级，安全）；"
                  f"命中量差异不是功能丢失")
        else:
            print(f"  MISS {label:22s} release=0  debug=0  <-- 两边都没有，才是真问题")
            failures.append(f"能力包无任何方法引用: {kw}")
    print()

    # ── 3. 被 R8 标记 REMOVED 的类，其能力是否仍可达 ──
    print("── 3. 被 R8 移除的类（BarcodeFormats / ImageDecoder）的能力存续性 ──")
    # BarcodeFormats 提供的能力：创建 Scanner + 码制常量
    bf_caps = [
        ("com.google.mlkit.vision.barcode.BarcodeScanning.getClient",
         "BarcodeFormats.createScanner() 内联后 -> 扫描器仍被创建"),
        ("com.google.mlkit.vision.barcode.BarcodeScannerOptions$Builder.setBarcodeFormats",
         "BarcodeFormats 的码制白名单内联后 -> 码制仍被显式开启"),
    ]
    # ImageDecoder 提供的能力：Bitmap 解码 + 相册识别
    id_caps = [
        ("com.google.mlkit.vision.common.InputImage.fromBitmap",
         "ImageDecoder 内联后 -> 相册位图仍被送入识别"),
        ("com.google.mlkit.vision.barcode.BarcodeScanner.process",
         "ImageDecoder 内联后 -> 仍调用 process 执行识别"),
    ]
    for fq, label in bf_caps + id_caps:
        if not check(fq, label):
            failures.append(f"内联后能力丢失: {fq}")
    print()

    # ── 4. 应用自身逻辑是否被内联（按 debug 里的类名查不到，改查调用特征）──
    print("── 4. 应用逻辑特征方法是否存续 ──")
    app_features = [
        ("android.os.Vibrator.vibrate", "FeedbackPlayer 的震动"),
        ("android.media.ToneGenerator.startTone", "FeedbackPlayer 的提示音"),
        ("android.content.ClipboardManager.setPrimaryClip", "复制到剪贴板"),
        ("androidx.camera.core.ImageAnalysis.setAnalyzer", "BarcodeAnalyzer 挂载"),
    ]
    for fq, label in app_features:
        if not check(fq, label):
            failures.append(f"应用能力丢失: {fq}（{label}）")
    print()

    # ── 结论 ──
    print("=" * 78)
    if failures:
        print(f"结论：{len(failures)} 处调用链问题")
        for f in failures:
            print(f"  !! {f}")
        print("CALLCHAIN_FAIL")
    else:
        print("结论：通过。所有关键调用链在 R8 混淆 + 内联后仍然完整可达。")
        print("CALLCHAIN_OK")
    print("=" * 78)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
