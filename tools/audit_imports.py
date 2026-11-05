#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Kotlin 源码静态审计（无需 JDK / Gradle / Android SDK）

审计三类问题：
  A. 包名与目录路径不一致（会导致 R 类/资源引用混乱，且 IDE 报错）
  B. 未使用的 import（噪音；也用来反查是不是把用到的符号误删了）
  C. 跨文件引用能否解析（import com.example.scanstock.X 指向的声明是否真的存在）

用法：python audit_imports.py <模块源码根目录>
"""

import os
import re
import sys
from collections import defaultdict

PKG_RE = re.compile(r'^\s*package\s+([\w.]+)', re.M)
IMPORT_RE = re.compile(r'^\s*import\s+([\w.]+)(?:\s+as\s+(\w+))?\s*$', re.M)

# 顶层声明：class / object / interface / fun / val / typealias / enum class
# 注意 fun 分支要能吃下泛型参数与扩展接收者，例如：
#   suspend fun <T> Task<T>.awaitResult(): T
#   fun <T> List<T>.foo()
DECL_RE = re.compile(
    r'^\s*(?:@\w+(?:\([^)]*\))?\s*)*'
    r'(?:public\s+|internal\s+|private\s+|abstract\s+|open\s+|sealed\s+|data\s+|enum\s+|annotation\s+|suspend\s+|inline\s+|operator\s+)*'
    r'(?:'
    r'fun\s+(?:<[^>\n]+>[ \t]*)?(?:[\w.<>,?\[\]][\w.<>,?\[\] \t]*\.)?(\w+)\s*[(<]'   # fun <T> Recv.name(
    r'|(class|object|interface|val|var|typealias)\s+(\w+)'        # class Foo / object Bar
    r')',
    re.M
)


def extract_decls(body: str):
    """返回 [(kind, name)]，兼容 fun 与非 fun 两种分支。"""
    out = []
    for m in DECL_RE.finditer(body):
        if m.group(1):          # fun 分支
            out.append(('fun', m.group(1)))
        elif m.group(2):        # class/object/... 分支
            out.append((m.group(2), m.group(3)))
    return out

# 通过委托语法使用、因而「看起来没被引用」的 import
KNOWN_DELEGATE_IMPORTS = {
    'getValue', 'setValue', 'provideDelegate',
    'ExperimentalGetImage',   # 用于 @OptIn 注解
    'OptIn', 'Suppress',
}

# Kotlin 内建 / 默认导入，不需要 import
BUILTIN_PREFIX = ('kotlin.', 'java.lang.', 'kotlinx.coroutines.flow.')


def strip_comments(src: str) -> str:
    """去掉块注释与行注释，避免注释里的符号干扰判断。"""
    src = re.sub(r'/\*.*?\*/', '', src, flags=re.S)
    src = re.sub(r'//[^\n]*', '', src)
    return src


def parse_file(path: str):
    with open(path, 'r', encoding='utf-8') as f:
        raw = f.read()
    clean = strip_comments(raw)

    pkg = PKG_RE.search(clean)
    pkg = pkg.group(1) if pkg else None

    imports = []
    for m in IMPORT_RE.finditer(clean):
        fq, alias = m.group(1), m.group(2)
        imports.append((fq, alias))

    # body = 去掉 import 行之后的正文
    body = IMPORT_RE.sub('', clean)
    if pkg:
        body = PKG_RE.sub('', body, count=1)

    decls = extract_decls(body)
    return {'path': path, 'pkg': pkg, 'imports': imports, 'body': body, 'decls': decls, 'raw': raw}


def main(roots) -> int:
    """
    roots[0] = 被审计的源码根目录（做 A/B/C 三类检查）
    roots[1:] = 仅用于建立符号索引的附加目录（例如 app/src/test/java，
                测试源码 import 主源码的符号，不该被判为「引用不可解析」）
    """
    if isinstance(roots, str):
        roots = [roots]
    primary = roots[0]
    index_roots = roots[1:]

    kt_files = []
    for dirpath, _, names in os.walk(primary):
        for n in names:
            if n.endswith('.kt'):
                kt_files.append(os.path.join(dirpath, n))
    kt_files.sort()

    if not kt_files:
        print('未找到任何 .kt 文件:', primary)
        return 1

    parsed = {p: parse_file(p) for p in kt_files}

    # 附加目录只为索引服务，不参与 A/B 检查
    extra_parsed = {}
    for r in index_roots:
        for dirpath, _, names in os.walk(r):
            for n in names:
                if n.endswith('.kt'):
                    p = os.path.join(dirpath, n)
                    extra_parsed[p] = parse_file(p)

    root = primary
    problems = defaultdict(list)

    # ── A. 包名 ↔ 路径 ──
    for path, info in parsed.items():
        if not info['pkg']:
            problems['A'].append(f'{rel(path, root)}: 缺少 package 声明')
            continue
        expected = os.path.dirname(os.path.relpath(path, root)).replace(os.sep, '/')
        actual = info['pkg'].replace('.', '/')
        if expected != actual:
            problems['A'].append(
                f'{rel(path, root)}: package={info["pkg"]} 但路径对应 {expected}')

    # ── 建索引：全限定名 → 声明（含附加目录）──
    decl_index = {}
    for path, info in list(parsed.items()) + list(extra_parsed.items()):
        if not info['pkg']:
            continue
        for kind, name in info['decls']:
            decl_index[f'{info["pkg"]}.{name}'] = (rel(path, root), kind, name)

    # ── B. 未使用 import ──
    for path, info in parsed.items():
        body = info['body']
        for fq, alias in info['imports']:
            short = alias or fq.rsplit('.', 1)[-1]
            if short in KNOWN_DELEGATE_IMPORTS:
                continue
            if fq.endswith('.*'):
                continue
            # 用词边界匹配，避免 Barcode 命中 BarcodeFormats 造成假阴性
            if not re.search(r'\b' + re.escape(short) + r'\b', body):
                problems['B'].append(f'{rel(path, root)}: 未使用的 import -> {fq}')

    # ── C. 跨文件引用可解析 ──
    for path, info in parsed.items():
        for fq, alias in info['imports']:
            if not fq.startswith('com.example.scanstock.'):
                continue
            short = alias or fq.rsplit('.', 1)[-1]
            if fq.endswith('.*'):
                continue
            if fq not in decl_index:
                # 可能是嵌套类或文件级私有声明，记录为待人工确认
                problems['C'].append(
                    f'{rel(path, root)}: import {fq} 未在本模块顶层声明中找到（'
                    f'可能是嵌套类/私有声明，需人工确认）')

    # ── 输出 ──
    total_decls = len(decl_index)
    print('=' * 74)
    print(f'Kotlin 静态审计  根目录: {root}')
    print(f'文件 {len(kt_files)} 个 · 顶层声明 {total_decls} 个 · '
          f'import 合计 {sum(len(i["imports"]) for i in parsed.values())} 条')
    print('=' * 74)

    titles = {
        'A': 'A. 包名与路径一致性',
        'B': 'B. 未使用的 import',
        'C': 'C. 跨文件引用可解析性',
    }
    for key in ('A', 'B', 'C'):
        items = problems[key]
        status = '通过' if not items else f'{len(items)} 处待处理'
        print(f'\n[{titles[key]}]  {status}')
        for it in items:
            print('   -', it)

    print('\n' + '=' * 74)
    if not (problems['A'] or problems['B'] or problems['C']):
        print('结论：全部通过。')
        return 0
    print(f'结论：A 类 {len(problems["A"])} · B 类 {len(problems["B"])} · '
          f'C 类 {len(problems["C"])}（C 类可能是嵌套类，以人工确认为准）')
    return 0


def rel(path: str, root: str) -> str:
    return os.path.relpath(path, root).replace(os.sep, '/')


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:] or ['.']))
