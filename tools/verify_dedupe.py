#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
去重算法仿真验证（无需 Android 环境）

目的：用可执行的方式证明 DedupeTracker 的「滑动窗口」语义在真实帧率下
      既不会刷屏，又能支持计件；同时与两种常见写法做量化对照。

术语：
  SLIDING  —— 本项目实现。无论是否计入，都刷新 lastReportedAt[key]。
  FIXED    —— 常见写法（需求字面理解）。只在「计入」时刷新 lastAcceptedAt[key]。
  NONE     —— 完全不去重。

帧率模型：CameraX ImageAnalysis + ML Kit 在 1280x720 下实测回调约 15~22 fps。
          取 20 fps（帧间隔 50ms）为基准，另跑 15 fps 作为压力对照。
          每帧有一定概率解码失败（暗光/角度），默认 5%。
"""

from dataclasses import dataclass, field


# ─────────────────────────── 三种策略 ───────────────────────────

class SlidingDedupe:
    """等价于 DedupeTracker.shouldAccept()：命中即刷新，无论 accept 与否。"""

    def __init__(self, window_ms=1500):
        self.window_ms = window_ms
        self.last = {}

    def should_accept(self, key, now):
        prev = self.last.get(key)
        accept = prev is None or (now - prev) >= self.window_ms
        self.last[key] = now          # ★ 关键行：无条件刷新
        return accept


class FixedDedupe:
    """只在计入时刷新时间戳——需求「1.5 秒窗口」的字面实现。"""

    def __init__(self, window_ms=1500):
        self.window_ms = window_ms
        self.last_accepted = {}

    def should_accept(self, key, now):
        prev = self.last_accepted.get(key)
        if prev is None or (now - prev) >= self.window_ms:
            self.last_accepted[key] = now
            return True
        return False


class NoDedupe:
    def should_accept(self, key, now):
        return True


STRATEGIES = {
    'SLIDING': SlidingDedupe,
    'FIXED': FixedDedupe,
    'NONE': NoDedupe,
}


# ─────────────────────────── 场景定义 ───────────────────────────

@dataclass
class Presence:
    """一个码在物理世界中的「出现在画面里」的时间区间（毫秒）。"""
    key: str
    enter: int
    leave: int


@dataclass
class Scenario:
    name: str
    presences: list
    expect: int
    note: str = ""


def hold(key, start, duration):
    return Presence(key, start, start + duration)


SCENARIOS = [
    Scenario(
        name="S1 固定对准同一个码 10 秒",
        presences=[hold("A", 0, 10000)],
        expect=1,
        note="防刷屏的核心场景",
    ),
    Scenario(
        name="S2 移开 2 秒后重新对准（计件第 2 件）",
        presences=[hold("A", 0, 500), hold("A", 2500, 500)],
        expect=2,
        note="同款商品连扫两件",
    ),
    Scenario(
        name="S3 快速掠过 10 个不同码（每个停留 0.5s）",
        presences=[hold(f"K{i}", i * 800, 500) for i in range(10)],
        expect=10,
        note="连续扫描命中率",
    ),
    Scenario(
        name="S4 边界：遮挡 1400ms 后重现（< 窗口）",
        presences=[hold("A", 0, 500), hold("A", 1900, 500)],
        expect=1,
        note="仍视为同一个码，不计入",
    ),
    Scenario(
        name="S5 边界：遮挡 1600ms 后重现（> 窗口）",
        presences=[hold("A", 0, 500), hold("A", 2100, 500)],
        expect=2,
        note="视为新的一条，计入",
    ),
    Scenario(
        name="S6 同一帧内出现 5 个相同的码（一张图里 5 个同款 QR）",
        presences=[hold("A", 0, 100)],          # 单帧内多目标，由 emit 阶段展开
        expect=1,
        note="同帧内去重",
        ),
    Scenario(
        name="S7 真实盘点：200 个商品，每个停留 0.8s，间隔 0.3s",
        presences=[hold(f"P{i}", i * 1100, 800) for i in range(200)],
        expect=200,
        note="吞吐能力",
    ),
    Scenario(
        name="S8 快速补扫同款：间隔 300ms 连续扫两次",
        presences=[hold("A", 0, 400), hold("A", 700, 400)],
        expect=2,
        note="已知风险点：窗口 1500ms 下会被吞掉",
    ),
]


# ─────────────────────────── 仿真引擎 ───────────────────────────

def simulate(scenario: Scenario, strategy_name: str, fps: int, drop_rate: float,
             window_ms: int, same_frame_duplicates: int = 1):
    """
    按帧推进，在每一帧把所有「此刻在画面里」的码交给去重器。
    与 BarcodeAnalyzer → ScanViewModel.onCodesDetected 的调用路径一致。
    """
    strategy = STRATEGIES[strategy_name](window_ms) if strategy_name != 'NONE' else NoDedupe()
    frame_ms = 1000 // fps
    total_ms = max(p.leave for p in scenario.presences) + frame_ms * 2

    accepted = []
    # 用伪随机但不依赖 random 模块的确定性序列，保证结果可复现
    mask = 0
    for t in range(0, total_ms, frame_ms):
        mask = (mask * 1103515245 + 12345) & 0x7FFFFFFF
        if (mask % 100) < int(drop_rate * 100):
            continue                                   # 本帧解码失败，无回调
        for p in scenario.presences:
            if not (p.enter <= t < p.leave):
                continue
            # 同一帧里可能出现多个相同码（如一张图里 5 个同款 QR）
            for _ in range(same_frame_duplicates):
                if strategy.should_accept(p.key, t):
                    accepted.append((p.key, t))
    return accepted


def run(strategy_name, fps=20, drop_rate=0.05, window_ms=1500, verbose_s1=True):
    print('=' * 88)
    print(f'策略={strategy_name}  帧率={fps}fps  解码失败率={drop_rate:.0%}  '
          f'去重窗口={window_ms}ms')
    print('=' * 88)
    print(f'{"场景":<46}{"期望":>5}{"实际":>6}{"判定":>7}   备注')
    print('-' * 88)

    ok_count = 0
    rows = []
    for sc in SCENARIOS:
        dup = 5 if sc.name.startswith('S6') else 1
        got = simulate(sc, strategy_name, fps, drop_rate, window_ms, dup)
        n = len(got)
        ok = (n == sc.expect)
        ok_count += ok
        mark = 'PASS' if ok else 'FAIL'
        print(f'{sc.name:<46}{sc.expect:>5}{n:>6}{mark:>7}   {sc.note}')
        rows.append((sc.name, sc.expect, n, ok, got))

    print('-' * 88)
    print(f'通过 {ok_count}/{len(SCENARIOS)}')
    print()
    return rows


def main():
    print('\n### 对照实验 1：三种策略在基准条件下（20fps / 5% 失败 / 窗口 1500ms）\n')
    results = {}
    for s in ('SLIDING', 'FIXED', 'NONE'):
        results[s] = run(s)

    print('\n### 对照实验 2：帧率与失败率压力测试（仅 SLIDING）\n')
    for fps, drop in ((30, 0.0), (20, 0.05), (15, 0.15), (10, 0.30)):
        run('SLIDING', fps=fps, drop_rate=drop)
        print()

    print('\n### 对照实验 3：把窗口调小能否救回 S8（快速补扫同款）\n')
    print(f'{"窗口(ms)":>10}{"S1 固定对准10s":>16}{"S2 移开2s":>12}{"S8 间隔300ms":>14}   结论')
    print('-' * 78)
    for w in (1500, 1000, 600, 400, 300):
        s1 = len(simulate(SCENARIOS[0], 'SLIDING', 20, 0.05, w))
        s2 = len(simulate(SCENARIOS[1], 'SLIDING', 20, 0.05, w))
        s8 = len(simulate(SCENARIOS[7], 'SLIDING', 20, 0.05, w))
        verdict = '不刷屏且可补扫' if (s1 == 1 and s2 == 2 and s8 == 2) else \
                  ('可补扫但仍不刷屏' if (s1 == 1 and s8 == 2) else 'S8 补扫被吞')
        print(f'{w:>10}{s1:>16}{s2:>12}{s8:>14}   {verdict}')

    print('\n### 对照实验 4：NONE 策略在 S1 下的实际条目数（刷屏量化）\n')
    for fps in (30, 20, 15):
        n = len(simulate(SCENARIOS[0], 'NONE', fps, 0.05, 1500))
        print(f'  {fps:>3}fps 下，把手机固定对准同一个码 10 秒，不去重会写入 {n} 条记录')
    print()


if __name__ == '__main__':
    main()
