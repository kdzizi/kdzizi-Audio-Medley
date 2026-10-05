import re, sys
rows = []
for line in open(sys.argv[1] if len(sys.argv) > 1 else 'stats2.txt', encoding='utf8', errors='ignore'):
    line = line.replace('\r', '')
    m = re.search(r'(\d\d:\d\d:\d\d)\.\d+.*Chunk:\s*(-?\d+)\s+(-?\d+)\s+(-?\d+)\s+(-?\d+)\s+(\d+)\s+(-?\d+)\s+(-?\d+)', line)
    if m:
        rows.append((m.group(1),) + tuple(int(x) for x in m.groups()[1:]))

print('时间      A瞬时  D精度  F_DAC  G微调')
for t, a, b, c, d, e, f, g in rows:
    print(f'{t}  {a:5d}  {d:5d}  {f:5d}  {g:5d}   {"<<<" if abs(a) > 10 else ""}')

inst = [r[1] for r in rows]
print()
print('样本', len(rows), ' 平均', round(sum(inst) / len(inst), 2), 'ms  最大绝对值', max(abs(x) for x in inst), 'ms')
