#!/usr/bin/env python3
"""Compares the map pixels of two runs: compare-maps.py <run-dir-a> <run-dir-b>
Reads maps/map_<id>.dat (gzip NBT) from each run and reports, per map id, whether the colors, center and scale match."""
import gzip, struct, sys
from pathlib import Path


def read_nbt(buf):
    pos = 0

    def u(fmt):
        nonlocal pos
        v = struct.unpack_from('>' + fmt, buf, pos)
        pos += struct.calcsize('>' + fmt)
        return v[0]

    def name():
        n = u('H')
        nonlocal pos
        s = buf[pos:pos + n].decode('utf-8', 'replace')
        pos += n
        return s

    def payload(t):
        nonlocal pos
        if t == 1: return u('b')
        if t == 2: return u('h')
        if t == 3: return u('i')
        if t == 4: return u('q')
        if t == 5: return u('f')
        if t == 6: return u('d')
        if t == 7:
            n = u('i'); v = buf[pos:pos + n]; pos += n; return bytes(v)
        if t == 8: return name()
        if t == 9:
            et = u('b'); n = u('i'); return [payload(et) for _ in range(n)]
        if t == 10:
            d = {}
            while True:
                tt = u('b')
                if tt == 0: return d
                k = name(); d[k] = payload(tt)
        if t == 11:
            n = u('i'); return [u('i') for _ in range(n)]
        if t == 12:
            n = u('i'); return [u('q') for _ in range(n)]
        raise ValueError(t)

    t = u('b'); name()
    return payload(t)


def maps(d):
    out = {}
    for f in sorted(p for p in Path(d, 'maps').glob('map_*.dat') if p.stem.split('_', 1)[1].isdigit()):
        root = read_nbt(gzip.decompress(f.read_bytes()))
        data = root.get('data', root)
        out[f.stem] = data
    return out


a, b = maps(sys.argv[1]), maps(sys.argv[2])
same = diff = 0
for k in sorted(set(a) & set(b), key=lambda s: int(s.split('_')[1])):
    x, y = a[k], b[k]
    keys = ('xCenter', 'zCenter', 'scale', 'dimension')
    meta_ok = all(x.get(q) == y.get(q) for q in keys)
    cx, cy = x.get('colors', b''), y.get('colors', b'')
    px = sum(1 for i in range(min(len(cx), len(cy))) if cx[i] != cy[i])
    painted = sum(1 for c in cx if c)
    ok = meta_ok and px == 0
    same += ok; diff += not ok
    print(f'{k}: center ({x.get("xCenter")}, {x.get("zCenter")}) scale {x.get("scale")} painted {painted} px, '
          f'{"identical" if ok else f"DIFFERENT ({px} px, meta {meta_ok})"}')
print(f'{same} identical, {diff} different, only in a: {sorted(set(a) - set(b))}, only in b: {sorted(set(b) - set(a))}')
