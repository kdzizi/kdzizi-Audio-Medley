import re
rows = []
for line in open('stats2.txt', encoding='utf8', errors='ignore'):
    line = line.replace('\r', '')
    m = re.search(r'Chunk:\s*(-?\d+)\s+(-?\d+)\s+(-?\d+)\s+(-?\d+)\s+(\d+)\s+(-?\d+)\s+(-?\d+)', line)
    if m:
        rows.append([int(x) for x in m.groups()])
a = [r[0] for r in rows]
d = [r[3] for r in rows]
print('A =', a)
print()
print('D =', d)
