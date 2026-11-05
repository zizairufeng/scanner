#!/usr/bin/env python3
"""
ScanStock 启动图标生成器
================================================================
一套参数同时产出：
  1) Adaptive Icon 矢量层（API 26+）：背景（渐变+高光）/ 前景 / monochrome（API 33 主题图标）
  2) 各密度 PNG（API 24/25 降级用）：方形圆角 + 圆形
  3) 可选预览图

设计说明
  深绿渐变底 + 白色四角取景框 + 亮绿条码。
  · 取景框是扫码的通用视觉语言，四角刻意开口（不是完整框），小尺寸下不会糊成一个方框；
  · 条码用宽窄交替的 4 根（6 / 4 / 5.5 / 6），比方块阵更像真条码，缩到 48dp 仍分得开；
  · 深绿底在浅色、深色、彩色壁纸三种桌面上都能立住（已用合成桌面验证）。

改图标 = 改下面的参数后重跑本脚本，不要手改生成的 XML/PNG。
用法： python3 tools/gen_icon.py [--preview 输出路径]
================================================================
"""
import argparse
import os

import numpy as np
from PIL import Image, ImageDraw

BASE = 108.0          # adaptive icon 标准画布
SS = 8                # 超采样倍数（抗锯齿靠它，不靠 PIL 的 AA）
RES = "app/src/main/res"

# ---- 背景：左上亮 → 右下暗 的对角渐变，再叠一层左上柔光 ------------------
BG_LIGHT = (30, 158, 116)             # #1E9E74 左上
BG_DARK = (4, 26, 22)                 # #041A16 右下
GLOW_RGB = (120, 240, 195)            # #78F0C3 高光色
GLOW_STOPS = [(0.0, 0.34), (0.55, 0.09), (1.0, 0.0)]   # (距离比例, alpha)
GLOW_CENTER = (0.30, 0.24)            # 相对坐标
GLOW_RADIUS = 0.60                    # 相对边长

# ---- 前景：四角取景框 + 条码 --------------------------------------------
FRAME = 30.0                          # 取景框外沿（30..78，边长 48）
STROKE = 5.5                          # 线宽
ARM = 19.0                            # 每个角的臂长（48-2×19=10 的开口）
BAR_Y0, BAR_Y1 = 45.5, 62.5           # 条码上下沿（垂直居中于 54）
# (左边缘 x, 宽度)：合计 30.5，左右各留 6 的白边，中心正好 54
BARS = [(38.75, 6.0), (47.75, 4.0), (54.75, 5.5), (63.25, 6.0)]

FG_FRAME = (255, 255, 255)            # 取景框纯白
FG_BAR = (123, 232, 176)              # #7BE8B0 条码亮绿

LEGACY_ZOOM = 1.12                    # 老图标没有安全区裁切，前景放大一点更饱满
LEGACY_RADIUS = 0.20                  # 方形图标圆角（相对边长）


# ---- 绘制 ---------------------------------------------------------------
def _gradient(size: int) -> Image.Image:
    """渐变 + 柔光，与矢量层（线性 gradient + 径向 gradient）逐像素等价。"""
    yy, xx = np.mgrid[0:size, 0:size]
    u = xx / max(size - 1, 1)
    v = yy / max(size - 1, 1)

    t = np.clip((u + v) / 2.0, 0.0, 1.0)[..., None]
    arr = np.asarray(BG_LIGHT, float) * (1 - t) + np.asarray(BG_DARK, float) * t
    arr /= 255.0

    d = np.sqrt((u - GLOW_CENTER[0]) ** 2 + (v - GLOW_CENTER[1]) ** 2) / GLOW_RADIUS
    a = np.interp(np.clip(d, 0, 1),
                  [s[0] for s in GLOW_STOPS],
                  [s[1] for s in GLOW_STOPS])[..., None]
    glow = np.asarray(GLOW_RGB, float) / 255.0
    arr = arr * (1 - a) + glow * a
    return Image.fromarray((arr * 255).astype(np.uint8), "RGB")


def _round_cap_line(draw, p0, p1, width, color):
    """PIL 的 line 只有方帽，自己补两端圆头。"""
    w = max(1, int(round(width)))
    draw.line([p0, p1], fill=color, width=w)
    r = w / 2.0
    for (x, y) in (p0, p1):
        draw.ellipse([x - r, y - r, x + r, y + r], fill=color)


def _corner_paths():
    """四个 L 形取景角（每角三点折线）。"""
    x0, x1 = FRAME, BASE - FRAME
    a = ARM
    return [
        [(x0, x0 + a), (x0, x0), (x0 + a, x0)],
        [(x1 - a, x0), (x1, x0), (x1, x0 + a)],
        [(x1, x1 - a), (x1, x1), (x1 - a, x1)],
        [(x0 + a, x1), (x0, x1), (x0, x1 - a)],
    ]


def render(size: int, *, zoom: float = 1.0, background: bool = True,
           shape: str = "square") -> Image.Image:
    """按 108 坐标系渲染一张位图。shape: square(圆角) / round(圆形) / none。"""
    S = size * SS
    k = S / BASE * zoom
    cx = cy = S / 2.0

    def P(x, y):
        return (cx + (x - BASE / 2) * k, cy + (y - BASE / 2) * k)

    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    if background:
        img.paste(_gradient(S), (0, 0))
    draw = ImageDraw.Draw(img)

    for pts in _corner_paths():
        scaled = [P(x, y) for (x, y) in pts]
        for a, b in zip(scaled, scaled[1:]):
            _round_cap_line(draw, a, b, STROKE * k, FG_FRAME)

    for x, w in BARS:
        draw.rectangle([P(x, BAR_Y0), P(x + w, BAR_Y1)], fill=FG_BAR)

    if shape == "round":
        mask = Image.new("L", (S, S), 0)
        ImageDraw.Draw(mask).ellipse([0, 0, S - 1, S - 1], fill=255)
        img.putalpha(mask)
    elif shape == "square":
        mask = Image.new("L", (S, S), 0)
        ImageDraw.Draw(mask).rounded_rectangle([0, 0, S - 1, S - 1],
                                               radius=LEGACY_RADIUS * S, fill=255)
        img.putalpha(mask)

    return img.resize((size, size), Image.LANCZOS)


# ---- 矢量层 -------------------------------------------------------------
def _fmt(v: float) -> str:
    return f"{v:g}"


def _hex(rgb) -> str:
    return "#FF%02X%02X%02X" % tuple(rgb)


def _vector(path: str, header: str, body: str):
    with open(path, "w", encoding="utf-8") as f:
        f.write(
            '<?xml version="1.0" encoding="utf-8"?>\n'
            f'{header}'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    xmlns:aapt="http://schemas.android.com/aapt"\n'
            f'    android:width="{_fmt(BASE)}dp"\n'
            f'    android:height="{_fmt(BASE)}dp"\n'
            f'    android:viewportWidth="{_fmt(BASE)}"\n'
            f'    android:viewportHeight="{_fmt(BASE)}">\n'
            f'{body}</vector>\n'
        )


def write_background(path: str):
    """两层：线性渐变铺满 + 左上径向柔光。"""
    cx = GLOW_CENTER[0] * BASE
    cy = GLOW_CENTER[1] * BASE
    r = GLOW_RADIUS * BASE
    stops = "".join(
        f'\n                <item android:offset="{_fmt(o)}" '
        f'android:color="#{int(round(a * 255)):02X}{GLOW_RGB[0]:02X}{GLOW_RGB[1]:02X}{GLOW_RGB[2]:02X}" />'
        for o, a in GLOW_STOPS
    )
    body = (
        '    <path android:pathData="M0,0 H108 V108 H0 Z">\n'
        '        <aapt:attr name="android:fillColor">\n'
        '            <gradient\n'
        '                android:type="linear"\n'
        '                android:startX="0"\n'
        '                android:startY="0"\n'
        '                android:endX="108"\n'
        '                android:endY="108">\n'
        f'                <item android:offset="0" android:color="{_hex(BG_LIGHT)}" />\n'
        f'                <item android:offset="1" android:color="{_hex(BG_DARK)}" />\n'
        '            </gradient>\n'
        '        </aapt:attr>\n'
        '    </path>\n'
        '    <path android:pathData="M0,0 H108 V108 H0 Z">\n'
        '        <aapt:attr name="android:fillColor">\n'
        '            <gradient\n'
        '                android:type="radial"\n'
        f'                android:centerX="{_fmt(cx)}"\n'
        f'                android:centerY="{_fmt(cy)}"\n'
        f'                android:gradientRadius="{_fmt(r)}">{stops}\n'
        '            </gradient>\n'
        '        </aapt:attr>\n'
        '    </path>\n'
    )
    header = (
        '<!-- 由 tools/gen_icon.py 生成，不要手改。\n'
        '     用 vector + aapt gradient 而不是 shape：startX/startY/endX/endY 能显式钉死\n'
        '     渐变方向，避开 android:angle 在不同系统上解释不一致、导致新旧图标明暗相反的问题。 -->\n'
    )
    _vector(path, header, body)


def _path_data(points):
    head = f"M{_fmt(points[0][0])},{_fmt(points[0][1])}"
    return head + "".join(f" L{_fmt(x)},{_fmt(y)}" for x, y in points[1:])


def write_foreground(path: str, color_frame: str, color_bar: str, header: str):
    strokes = "\n".join(
        "    <path\n"
        f'        android:pathData="{_path_data(pl)}"\n'
        f'        android:strokeColor="{color_frame}"\n'
        f'        android:strokeWidth="{_fmt(STROKE)}"\n'
        '        android:strokeLineCap="round"\n'
        '        android:strokeLineJoin="round"\n'
        '        android:fillColor="#00000000" />'
        for pl in _corner_paths()
    )
    bars = "\n".join(
        '    <path\n'
        f'        android:pathData="M{_fmt(x)},{_fmt(BAR_Y0)} H{_fmt(x + w)} '
        f'V{_fmt(BAR_Y1)} H{_fmt(x)} Z"\n'
        f'        android:fillColor="{color_bar}" />'
        for x, w in BARS
    )
    _vector(path, header, f'{strokes}\n{bars}\n')


def write_adaptive(path: str, monochrome: bool):
    mono = ('\n    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />'
            if monochrome else "")
    with open(path, "w", encoding="utf-8") as f:
        f.write(
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
            '    <background android:drawable="@drawable/ic_launcher_background" />\n'
            '    <foreground android:drawable="@drawable/ic_launcher_foreground" />'
            f'{mono}\n'
            '</adaptive-icon>\n'
        )


# ---- 预览 ---------------------------------------------------------------
def preview(path: str):
    """一张图看全：大图 + 三种桌面上的 96/72/48 与圆形版。"""
    W, H = 1180, 620
    canvas = Image.new("RGB", (W, H), (250, 250, 251))
    d = ImageDraw.Draw(canvas)
    d.text((40, 16), "ScanStock launcher icon", fill=(70, 70, 70))

    big = render(340)
    canvas.paste(big, (40, 44), big)

    y = 420
    for label, bgc in (("light desktop", (245, 246, 247)),
                       ("dark desktop", (24, 26, 28)),
                       ("color wallpaper", (120, 150, 190))):
        box = Image.new("RGB", (700, 64), bgc)
        x = 16
        for s in (96, 72, 48):
            ic = render(s)
            box.paste(ic, (x, 32 - s // 2), ic)
            x += s + 16
        cir = render(72, shape="round")
        box.paste(cir, (x, 32 - 36), cir)
        canvas.paste(box, (40, y))
        d.text((748, y + 24), label, fill=(110, 110, 110))
        y += 66

    canvas.save(path)
    return path


# ---- 落盘 ---------------------------------------------------------------
DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}


def write_all():
    drawable = os.path.join(RES, "drawable")
    os.makedirs(drawable, exist_ok=True)

    write_background(os.path.join(drawable, "ic_launcher_background.xml"))
    write_foreground(os.path.join(drawable, "ic_launcher_foreground.xml"),
                     _hex(FG_FRAME), _hex(FG_BAR),
                     '<!-- 由 tools/gen_icon.py 生成：白色四角取景框 + 亮绿条码 -->\n')
    write_foreground(os.path.join(drawable, "ic_launcher_monochrome.xml"),
                     "#FFFFFFFF", "#FFFFFFFF",
                     '<!-- 由 tools/gen_icon.py 生成：Android 13+ 主题图标的单色形状层 -->\n')

    for v in ("-v26", "-v33"):
        folder = os.path.join(RES, f"mipmap-anydpi{v}")
        os.makedirs(folder, exist_ok=True)
        for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
            write_adaptive(os.path.join(folder, name), monochrome=(v == "-v33"))

    for name, px in DENSITIES.items():
        folder = os.path.join(RES, f"mipmap-{name}")
        os.makedirs(folder, exist_ok=True)
        render(px, zoom=LEGACY_ZOOM, shape="square").save(
            os.path.join(folder, "ic_launcher.png"))
        render(px, zoom=LEGACY_ZOOM, shape="round").save(
            os.path.join(folder, "ic_launcher_round.png"))


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--preview", default="")
    args = ap.parse_args()
    write_all()
    if args.preview:
        print(preview(args.preview))
    print("icon generated")
