#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
CopyFormatter 交叉验证（无需 Kotlin 环境）

做法：把 CopyFormatter.kt 的 format() / formatSingle() / buildCsv() / csvEscape()
逐行复刻成 Python，然后**断言与 Kotlin 单元测试里完全相同的那批期望字符串**。
两份独立转录如果都通过，说明对同一组输入产出了同样的输出，
即 Kotlin 测试里的字面量期望值不是拍脑袋写的。

用法：python verify_formatter.py
"""

EOL = "\n"


# ───────────── 逐行复刻 CopyFormatter.kt ─────────────

def csv_escape(raw: str) -> str:
    need_quote = any(c in raw for c in (',', '"', '\n', '\r'))
    if not need_quote:
        return raw
    return '"' + raw.replace('"', '""') + '"'


def build_csv(items) -> str:
    parts = ["序号,码制,内容,时间"]
    for it in items:
        parts.append(
            ",".join([
                str(it['seq']),
                csv_escape(it['formatName']),
                csv_escape(it['value']),
                csv_escape(it['timeText']),
            ])
        )
    return EOL.join(parts).rstrip('\n')


def fmt(items, format_name: str) -> str:
    if not items:
        return ""
    if format_name == 'NUMBERED_TAB':
        return EOL.join(f"{it['seq']}\t{it['value']}" for it in items)
    if format_name == 'PLAIN':
        return EOL.join(it['value'] for it in items)
    if format_name == 'CSV':
        return build_csv(items)
    raise ValueError(format_name)


def fmt_single(it) -> str:
    return it['value']


def item(seq, value, format_name="EAN-13", time_text="10:00:00.000"):
    return {'seq': seq, 'formatName': format_name, 'value': value, 'timeText': time_text}


# ───────────── 与 Kotlin 测试一一对应的断言 ─────────────

def main():
    two = [item(1, "6901234567892"), item(2, "6923450657713")]
    checks = []

    def check(desc, actual, expected):
        ok = actual == expected
        checks.append((desc, ok, actual, expected))
        return ok

    # DedupeTrackerTest 无关；这里是 CopyFormatterTest 的全部期望
    check("默认格式 序号+Tab",
          fmt(two, 'NUMBERED_TAB'),
          "1\t6901234567892\n2\t6923450657713")

    check("默认格式无 CR",
          str('\r' in fmt(two, 'NUMBERED_TAB')),
          "False")

    check("seq 不连续时保持原样",
          fmt([item(1, "AAA"), item(3, "BBB"), item(7, "CCC")], 'NUMBERED_TAB'),
          "1\tAAA\n3\tBBB\n7\tCCC")

    check("纯文本只输出码值",
          fmt(two, 'PLAIN'),
          "6901234567892\n6923450657713")

    check("纯文本保留重复码值",
          fmt([item(1, "SAME"), item(2, "SAME")], 'PLAIN'),
          "SAME\nSAME")

    csv_text = fmt(two, 'CSV')
    lines = csv_text.split('\n')
    check("CSV 表头", lines[0], "序号,码制,内容,时间")
    check("CSV 第 1 行", lines[1], "1,EAN-13,6901234567892,10:00:00.000")
    check("CSV 第 2 行", lines[2], "2,EAN-13,6923450657713,10:00:00.000")
    check("CSV 行数", str(len(lines)), "3")
    check("CSV 结尾无换行", str(csv_text.endswith('\n')), "False")

    tricky = fmt([item(1, '{"a":1,"b":2}', "QR Code")], 'CSV').split('\n')[1]
    check("含逗号字段被引号包裹并双写内部引号",
          tricky,
          '1,QR Code,"{""a"":1,""b"":2}",10:00:00.000')

    quoted = fmt([item(1, 'AB"CD', "Code-128")], 'CSV').split('\n')[1]
    check("含引号字段双写", quoted, '1,Code-128,"AB""CD",10:00:00.000')

    multiline = fmt([item(1, 'LINE1\nLINE2', "QR Code")], 'CSV')
    check("含换行字段整体加引号", str('"LINE1\nLINE2"' in multiline), "True")

    check("普通字段不加引号",
          str('"6901234567892"' in csv_text), "False")

    check("空列表 → 空串(numbered)", fmt([], 'NUMBERED_TAB'), "")
    check("空列表 → 空串(plain)", fmt([], 'PLAIN'), "")
    check("空列表 → 空串(csv)", fmt([], 'CSV'), "")
    check("单条复制只给码值", fmt_single(two[0]), "6901234567892")

    # ── 输出 ──
    print('=' * 78)
    print('CopyFormatter 交叉验证（Python 复刻 vs Kotlin 测试期望字面量）')
    print('=' * 78)
    print(f'{"检查项":<38}{"结果":>6}')
    print('-' * 78)
    passed = 0
    for desc, ok, actual, expected in checks:
        if ok:
            passed += 1
            print(f'{desc:<38}{"PASS":>6}')
        else:
            print(f'{desc:<38}{"FAIL":>6}')
            print(f'      实际: {actual!r}')
            print(f'      期望: {expected!r}')
    print('-' * 78)
    print(f'通过 {passed}/{len(checks)}')
    print()

    # 额外打印真实数据集样例，便于人工核对「复制全部」的真实观感
    demo = [
        item(1, "6901234567892", "EAN-13", "14:32:07.418"),
        item(2, "6901234567895", "EAN-13", "14:32:09.102"),
        item(3, "SN-2026-09-17-00417", "Code-128", "14:32:11.880"),
        item(4, '{"dev":"A-001","ok":true}', "QR Code", "14:32:13.245"),
    ]
    print('实际输出样例（默认格式）：')
    print('-' * 78)
    for line in fmt(demo, 'NUMBERED_TAB').split('\n'):
        print('  ', repr(line))
    print()
    print('实际输出样例（CSV 格式）：')
    print('-' * 78)
    for line in fmt(demo, 'CSV').split('\n'):
        print('  ', repr(line))
    print()
    return 0 if passed == len(checks) else 1


if __name__ == '__main__':
    raise SystemExit(main())
