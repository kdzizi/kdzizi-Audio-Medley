import re, json
rows = []
for line in open('stats2.txt', encoding='utf8', errors='ignore'):
    line = line.replace('\r', '')
    m = re.search(r'Chunk:\s*(-?\d+)\s+(-?\d+)\s+(-?\d+)\s+(-?\d+)\s+(\d+)\s+(-?\d+)\s+(-?\d+)', line)
    if m:
        rows.append([int(x) for x in m.groups()])

a = [r[0] for r in rows]
d = [r[3] for r in rows]
W = 620.0
def y(v):
    return 150.0 - (v + 10) / 45.0 * 130.0

pts_a = ' '.join('%.1f,%.1f' % (i * W / (len(a) - 1), y(v)) for i, v in enumerate(a))
pts_d = ' '.join('%.1f,%.1f' % (i * W / (len(d) - 1), y(v)) for i, v in enumerate(d))
out = {
    'n': len(a),
    'pts_a': pts_a,
    'pts_d': pts_d,
    'y0': round(y(0), 1), 'y10': round(y(10), 1), 'y30': round(y(30), 1), 'ynega': round(y(-10), 1),
    'avg': round(sum(a) / len(a), 2),
    'max': max(a), 'min': min(a),
    'over10': sum(1 for x in a if abs(x) > 10),
    'avgd': round(sum(d) / len(d), 2),
    'maxd': max(d),
}
print(json.dumps(out, ensure_ascii=False))
