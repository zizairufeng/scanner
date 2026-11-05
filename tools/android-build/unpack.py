"""解压 JDK / Gradle / cmdline-tools，并按 Android SDK 要求的目录名摆放。

cmdline-tools 的 zip 内层目录叫 cmdline-tools/，但 sdkmanager 要求它必须
位于 <sdk>/cmdline-tools/latest/，所以这里要重命名一层。
"""
import os
import shutil
import sys
import time
import zipfile

BASE = r"C:\Users\czk\androidbuild"
DL = os.path.join(BASE, "dl")
SDK = os.path.join(BASE, "sdk")


def unzip_with_progress(zip_path, dest, strip_prefix=None, rename_inner=None):
    """解压 zip。

    strip_prefix: 若提供，去掉该前缀（用于把 zip 内层目录剥掉）
    rename_inner: {原名: 新名}，改名顶层目录
    """
    print(f"\n>>> 解压 {os.path.basename(zip_path)} -> {dest}")
    os.makedirs(dest, exist_ok=True)
    t0 = time.time()

    with zipfile.ZipFile(zip_path) as z:
        members = z.namelist()
        total = len(members)
        print(f"    条目数 {total}，压缩体积 {os.path.getsize(zip_path)/1024/1024:.1f}MB")

        for i, m in enumerate(members, 1):
            target_rel = m
            if strip_prefix and target_rel.startswith(strip_prefix):
                target_rel = target_rel[len(strip_prefix):]
            if not target_rel:
                continue

            parts = target_rel.split("/")
            if rename_inner and parts[0] in rename_inner:
                parts[0] = rename_inner[parts[0]]
            target_rel = "/".join(parts)

            target_path = os.path.join(dest, *parts)
            if m.endswith("/"):
                os.makedirs(target_path, exist_ok=True)
                continue

            os.makedirs(os.path.dirname(target_path), exist_ok=True)
            with z.open(m) as src, open(target_path, "wb") as out:
                shutil.copyfileobj(src, out, 1024 * 256)

            if i % 3000 == 0:
                print(f"    ... {i}/{total}")

    print(f"    完成，用时 {time.time()-t0:.1f}s")


def main():
    jobs = [
        # (zip 文件, 目标目录, 剥离前缀, 改名)
        ("openjdk-17.0.2_windows-x64_bin.zip", os.path.join(BASE, "jdk"),
         None, {"jdk-17.0.2": "jdk17"}),
        ("gradle-8.9-bin.zip", os.path.join(BASE, "gradle"),
         None, {"gradle-8.9": "gradle-8.9"}),
        # 注意：sdkmanager 强制要求自己位于 <sdk>/cmdline-tools/latest/bin/，
        # 所以必须剥掉内层 cmdline-tools/ 前缀后直接落进 latest/ 目录。
        ("commandlinetools-win-11076708_latest.zip",
         os.path.join(SDK, "cmdline-tools", "latest"),
         "cmdline-tools/", None),
    ]

    for zip_name, dest, strip, rename in jobs:
        zp = os.path.join(DL, zip_name)
        if not os.path.exists(zp):
            print(f"!! 缺少 {zp}，跳过")
            return 1
        unzip_with_progress(zp, dest, strip, rename)

    print("\n" + "=" * 70)
    print("解压结果检查")
    print("=" * 70)

    checks = [
        ("java.exe", os.path.join(BASE, "jdk", "jdk17", "bin", "java.exe")),
        ("keytool.exe", os.path.join(BASE, "jdk", "jdk17", "bin", "keytool.exe")),
        ("gradle.bat", os.path.join(BASE, "gradle", "gradle-8.9", "bin", "gradle.bat")),
        ("sdkmanager.bat", os.path.join(SDK, "cmdline-tools", "latest", "bin", "sdkmanager.bat")),
        ("avdmanager.bat", os.path.join(SDK, "cmdline-tools", "latest", "bin", "avdmanager.bat")),
    ]
    ok = True
    for label, path in checks:
        exists = os.path.exists(path)
        print(f"  {'OK  ' if exists else 'MISS'} {label:18s} {path}")
        if not exists:
            ok = False

    print()
    print("UNPACK_OK" if ok else "UNPACK_INCOMPLETE")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
