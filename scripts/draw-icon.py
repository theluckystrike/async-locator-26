#!/usr/bin/env python3
"""Draws the 512 px mod icon: a treasure map with an X, and a circular arrow for the background thread.
Usage: draw-icon.py <out.png>   (also writes <out>-128.png)"""
import math, sys
from PIL import Image, ImageDraw

out = sys.argv[1]
S = 512
im = Image.new('RGBA', (S, S), (0, 0, 0, 0))
d = ImageDraw.Draw(im)
d.rounded_rectangle([0, 0, S - 1, S - 1], 96, fill=(22, 30, 40))
# map sheet
d.polygon([(96, 120), (200, 96), (312, 128), (416, 104), (416, 392), (312, 416), (200, 384), (96, 408)],
          fill=(222, 196, 140))
d.polygon([(200, 96), (312, 128), (312, 416), (200, 384)], fill=(206, 178, 120))
# land shapes
d.ellipse([130, 170, 230, 250], fill=(118, 170, 90))
d.ellipse([250, 260, 380, 350], fill=(118, 170, 90))
d.ellipse([290, 150, 360, 200], fill=(100, 150, 200))
# dotted path
pts = [(150, 330), (190, 300), (230, 290), (270, 250), (300, 220)]
for x, y in pts:
    d.ellipse([x - 7, y - 7, x + 7, y + 7], fill=(150, 60, 40))
# red X
cx, cy, r = 330, 200, 26
d.line([(cx - r, cy - r), (cx + r, cy + r)], fill=(200, 40, 40), width=16)
d.line([(cx - r, cy + r), (cx + r, cy - r)], fill=(200, 40, 40), width=16)
# circular arrow badge, bottom right
bx, by, br = 392, 392, 92
d.ellipse([bx - br, by - br, bx + br, by + br], fill=(40, 120, 210), outline=(22, 30, 40), width=10)
ar = 52
d.arc([bx - ar, by - ar, bx + ar, by + ar], 200, 520 - 40, fill=(245, 248, 252), width=16)
a = math.radians(480 - 40)
tx, ty = bx + ar * math.cos(a), by + ar * math.sin(a)
d.polygon([(tx + 26, ty - 4), (tx - 14, ty - 22), (tx - 6, ty + 22)], fill=(245, 248, 252))
im.save(out)
im.resize((128, 128), Image.LANCZOS).save(out.replace('.png', '-128.png'))
