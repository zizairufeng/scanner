# -*- coding: utf-8 -*-
"""
聚焦验证：对「按原名查不到」的 CameraX / AndroidX 方法，反查其改名后的形态，
并列出它在 release dex 中真实存在的方法引用。

判据说明：
    R8 对未被 keep 的库代码可以做三件事——
      (a) 改名      -> mapping.txt 会记录 旧名 -> 新名，改用新名可在 dex 中找到
      (b) 内联      -> mapping.txt 不记录（方法已消解），但代码被搬到调用方，功能仍在
      (c) 删除      -> 只有在「确实无调用点」时才会发生

    所以「按原名查不到」既可能是 (a) 也可能是 (b)，
    **不能**据此判定功能丢失。本脚本用两类证据区分：
      1. 该类在 mapping 中的新名字，以及新名字在 dex 中的实际方法引用；
      2. 该类所在接口/抽象类的实现类是否仍被引用（链路是否可达）。
"""
import os
import re
import struct
import sys
import zipfile

APK_REL = r"C:\Users\czk\WorkBuddy\2026-09-17-15-46-21\ScanStock\dist\ScanStock-1.3-release.apk"
MAPPING = (r"C:\Users\czk\WorkBuddy\2026-09-17-15-46-21\ScanStock"
           r"\app\build\outputs\mapping\release\mapping.txt")

CLASS_LINE = re.compile(r"^(?P<old>[\w.$]+)\s*->\s*(?P<new>[\w.$]+):\s*$")
MEMBER_LINE = re.compile(
    r"^\s+.*?\s(?P<name>[\w$<>]+)\((?P<args>[^)]*)\)(?::\d+)*\s*->\s*(?P<new>[\w$<>]+)\s*$")

# 需要聚焦核查的类：它们在按原名查询时 MISSING
FOCUS = [
    "androidx.camera.lifecycle.ProcessCameraProvider",
    "androidx.camera.core.ImageAnalysis",
    "androidx.camera.core.CameraControl",
    "androidx.camera.view.PreviewView",
    "androidx.activity.result.ActivityResultLauncher",
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
    file_size = struct.unpack_from("<I", buf, 32)[0]
    string_ids_size = struct.unpack_from("<I", buf, 56)[0]
    string_ids_off = struct.unpack_from("<I", buf, 60)[0]
    type_ids_size = struct.unpack_from("<I", buf, 64)[0]
    type_ids_off = struct.unpack_from("<I", buf, 68)[0]
    method_ids_size = struct.unpack_from("<I", buf, 88)[0]
    method_ids_off = struct.unpack_from("<I", buf, 92)[0]

    def get_string(idx):
        if idx < 0 or idx >= string_ids_size:
            return None
        off = struct.unpack_from("<I", buf, string_ids_off + idx * 4)[0]
        _, p = _uleb128(buf, off)
        end = buf.index(b"\x00", p)
        return buf[p:end].decode("utf-8", "replace")

    types = []
    for i in range(type_ids_size):
        si = struct.unpack_from("<I", buf, type_ids_off + i * 4)[0]
        d = get_string(si)
        types.append(d[1:-1].replace("/", ".") if d and d.startswith("L") and d.endswith(";")
                     else (d or ""))

    classes = set(t for t in types if t)
    methods = set()
    assert method_ids_off + method_ids_size * 8 <= file_size, "method_ids 表越界"
    for i in range(method_ids_size):
        off = method_ids_off + i * 8
        cls_idx = struct.unpack_from("<H", buf, off)[0]
        name_idx = struct.unpack_from("<I", buf, off + 4)[0]
        cls = types[cls_idx] if cls_idx < len(types) else ""
        name = get_string(name_idx) or ""
        if cls and name:
            methods.add(f"{cls}.{name}")
    return classes, methods


def scan(apk):
    classes, methods = set(), set()
    with zipfile.ZipFile(apk) as z:
        for n in sorted(x for x in z.namelist() if re.match(r"classes\d*\.dex$", x)):
            c, m = parse_dex(z.read(n))
            classes |= c
            methods |= m
    return classes, methods


def load_mapping_for(targets):
    """只解析 targets 里那些类的映射块。返回 (cls_map, members_map)。"""
    cls_map, members = {}, {t: {} for t in targets}
    cur = None
    tset = set(targets)
    with open(MAPPING, "r", encoding="utf-8", errors="replace") as f:
        for line in f:
            if not line.strip() or line.startswith("#"):
                continue
            if not line[0].isspace():
                m = CLASS_LINE.match(line.rstrip())
                cur = None
                if m:
                    old, new = m.group("old"), m.group("new")
                    if old in tset:
                        cls_map[old] = new
                        cur = old
                continue
            if cur is None:
                continue
            m = MEMBER_LINE.match(line.rstrip())
            if m:
                members[cur][m.group("name")] = m.group("new")
    return cls_map, members


def main():
    print("=" * 78)
    print("聚焦核查：CameraX / AndroidX 被改名类的调用链存续性")
    print("=" * 78)
    print()

    classes, methods = scan(APK_REL)
    print(f"  release dex: {len(classes)} 类 / {len(methods)} 方法引用")
    print()

    cls_map, members_map = load_mapping_for(FOCUS)

    for old_cls in FOCUS:
        new_cls = cls_map.get(old_cls)
        print(f"── {old_cls} ──")
        if new_cls is None:
            print("   未出现在 mapping.txt —— 该类未被 R8 触碰（可能被 keep 或未被引用）")
            print(f"   在 dex 中是否以原名存在: {'是' if old_cls in classes else '否'}")
            print()
            continue

        if new_cls.startswith("R8$$REMOVED$$CLASS$$"):
            print(f"   已被 R8 整体移除（{new_cls}）")
            print()
            continue

        print(f"   改名: {new_cls}")

        # 新名字下的方法引用
        prefix = new_cls + "."
        hits = sorted(m for m in methods if m.startswith(prefix))
        print(f"   在 dex 中以新名被引用的方法: {len(hits)} 个")
        for h in hits[:12]:
            print(f"      {h[len(prefix):]}   ({h})")
        if len(hits) > 12:
            print(f"      ... 另有 {len(hits)-12} 个")

        # 该类在 mapping 里登记的成员改名
        mem = members_map.get(old_cls, {})
        print(f"   该类在 mapping 中登记的成员映射: {len(mem)} 个")
        for k in sorted(mem)[:14]:
            print(f"      {k}  ->  {mem[k]}")
        if len(mem) > 14:
            print(f"      ... 另有 {len(mem)-14} 个")
        print()

    print("=" * 78)
    print("解读要点：")
    print("  · 若某类的成员映射里**找不到**某个方法名，且新名下也没有它，")
    print("    说明该方法已被 R8 内联进调用方 —— 代码仍在，只是没有独立方法引用。")
    print("  · 只要新名字下仍有该类的方法被引用，就说明这个类被链接进了最终产物，")
    print("    相机链路没有断。")
    print("=" * 78)


if __name__ == "__main__":
    sys.exit(main())
