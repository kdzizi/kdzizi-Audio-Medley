"""解析 snapclient 的 Stats 日志，量化同步误差。

Stats 各列（源码 client/stream.cpp，单位：值/100 = ms）：
  A age        : 当前 chunk 相对播放时刻的瞬时偏差
  B miniMedian : 极短窗口中位偏差
  C shortMedian: 短窗口中位偏差
  D median     : 长窗口中位偏差 —— 核心同步精度指标
  E buffer size: 环形缓冲容量（固定）
  F dacTime    : 输出缓冲/DAC 时间（播放链路固有延迟，不是同步误差）
  G frame_delta: 帧间微调量
"""
import re, sys

path = sys.argv[1] if len(sys.argv) > 1 else 'stats.txt'
rows = []
for line in open(path, encoding='utf8', errors='ignore'):
    m = re.search(r'Chunk:\s*(-?\d+)\s+(-?\d+)\s+(-?\d+)\s+(-?\d+)\s+(\d+)\s+(-?\d+)\s+(-?\d+)', line)
    if m:
        rows.append([int(x) for x in m.groups()])

print('样本数:', len(rows))
if not rows:
    sys.exit(0)

names = ['A 瞬时偏差', 'B 极短窗', 'C 短窗中位', 'D 长窗中位(同步精度)', 'E 缓冲容量', 'F DAC缓冲', 'G 帧微调']
for i, nm in enumerate(names):
    col = [r[i] for r in rows]
    print(f'{nm:24s}: min {min(col):5d}  max {max(col):5d}  avg {sum(col)/len(col):7.2f}')

inst = [r[0] for r in rows]
med = [r[3] for r in rows]
over = [x for x in inst if abs(x) > 10]
print()
print(f'瞬时偏差绝对值 >10ms 的采样: {len(over)} / {len(rows)}')
print(f'瞬时偏差绝对值 >30ms 的采样: {len([x for x in inst if abs(x) > 30])} / {len(rows)}')
print(f'同步精度(D) 超过 5ms 的采样: {len([x for x in med if abs(x) > 5])} / {len(rows)}')
