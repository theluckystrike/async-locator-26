#!/usr/bin/env python3
"""Renders the two gallery images from real-run evidence.
Usage: render-gallery.py <bench-26.3.json> <console.log of a mod run> <outdir>"""
import json, re, statistics, sys
from PIL import Image, ImageDraw, ImageFont

bench, console, out = sys.argv[1:4]
FONT = '/System/Library/Fonts/Menlo.ttc'
SURF, INK, INK2, GRID = (26, 26, 25), (240, 239, 236), (195, 194, 183), (60, 60, 58)
VAN, MOD = (217, 89, 38), (57, 135, 229)   # validated pair on the dark surface


def font(n):
    return ImageFont.truetype(FONT, n)


def chart(path):
    data = json.load(open(bench))['cases']
    names = {'idle baseline': 'Idle (20 s)', 'minecraft:mansion': '/locate mansion',
             'minecraft:ancient_city': '/locate ancient_city', 'minecraft:jungle_pyramid': '/locate jungle_pyramid',
             'burst 16 treasure maps': '16 treasure maps at once'}
    cases = [c for c in names if c in data]
    vals = {c: {m: statistics.median(data[c][m]['max_ms']) for m in ('vanilla', 'mod')} for c in cases}
    W, H = 1600, 900
    im = Image.new('RGB', (W, H), SURF)
    d = ImageDraw.Draw(im)
    d.text((60, 40), 'Longest server tick (ms), Fabric 26.3, same seed', font=font(36), fill=INK)
    d.text((60, 92), 'Median of 3 runs per mode. One tick should take 50 ms or less.', font=font(22), fill=INK2)
    # legend
    lx = 60
    for label, col in (('Vanilla', VAN), ('With Async Locator 26', MOD)):
        d.rounded_rectangle([lx, 140, lx + 22, 162], 4, fill=col)
        d.text((lx + 32, 137), label, font=font(22), fill=INK)
        lx += 32 + d.textlength(label, font=font(22)) + 40
    left, right, top = 430, W - 160, 200
    vmax = max(max(v.values()) for v in vals.values())
    scale = (right - left) / (vmax * 1.05)
    row = (H - top - 80) / len(cases)
    bh = 26
    # grid + 50 ms budget
    for t in range(0, int(vmax * 1.05) + 1, 500):
        x = left + t * scale
        d.line([x, top - 10, x, H - 70], fill=GRID, width=1)
        d.text((x - 20, H - 60), f'{t}', font=font(18), fill=INK2)
    bx = left + 50 * scale
    for y in range(top - 10, H - 70, 12):
        d.line([bx, y, bx, y + 6], fill=INK2, width=2)
    d.text((bx + 6, top - 34), '50 ms', font=font(18), fill=INK2)
    for i, c in enumerate(cases):
        y0 = top + i * row + 10
        d.text((60, y0 + 18), names[c], font=font(24), fill=INK)
        for j, (m, col) in enumerate((('vanilla', VAN), ('mod', MOD))):
            v = vals[c][m]
            y = y0 + j * (bh + 4)
            x1 = left + max(v * scale, 3)
            d.rounded_rectangle([left, y, x1, y + bh], 4, fill=col)
            label = f'{v:g} ms'
            lx = max(x1 + 10, bx + 10) if v * scale < 60 else x1 + 10   # keep short labels clear of the 50 ms line
            d.text((lx, y + 2), label, font=font(20), fill=INK)
    d.line([left, top - 10, left, H - 70], fill=INK2, width=2)
    im.save(path)


def console_shot(path):
    keep = re.compile(r'Locating .* in the background|AsyncLocator-|The nearest minecraft|Locating element|The game time is|'
                      r'Started \d+ locating|Async Locator ready|Done \(')
    rows = []
    for ln in open(console, encoding='utf-8', errors='replace'):
        ln = ln.rstrip('\n')
        if keep.search(ln) and 'on_treasure_maps' not in ln and '[main/' not in ln:
            ln = ln.replace('System chat: ', '')
            rows.append(ln[:150])
    rows = rows[:26]
    f = font(19)
    lh = 30
    W, H = 1920, 90 + lh * len(rows) + 40
    im = Image.new('RGB', (W, H), (18, 22, 28))
    d = ImageDraw.Draw(im)
    d.rectangle([0, 0, W, 50], fill=(34, 40, 50))
    for i, c in enumerate([(237, 106, 94), (245, 191, 79), (98, 197, 84)]):
        d.ellipse([18 + i * 28, 17, 34 + i * 28, 33], fill=c)
    d.text((120, 13), 'Fabric 26.3 dedicated server with Async Locator 26 (real run, console)', font=font(22), fill=(220, 226, 234))
    for i, ln in enumerate(rows):
        col = (200, 208, 218)
        if 'AsyncLocator-' in ln:
            col = (110, 170, 240)
        elif 'The nearest' in ln:
            col = (120, 200, 120)
        elif 'game time' in ln:
            col = (235, 190, 90)
        d.text((24, 76 + i * lh), ln, font=f, fill=col)
    im.save(path)


chart(f'{out}/tick-times.png')
console_shot(f'{out}/console-26.3.png')
print('wrote', out)
