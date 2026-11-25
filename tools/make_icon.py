#!/usr/bin/env python3
"""Ícono original de DeltaDroid / DeltaVita: degradado violeta-azul con un mando estilizado.
No usa el logo de Delta. Uso: make_icon.py salida.png [tamaño]"""
import sys
from PIL import Image, ImageDraw


def icon(size: int) -> Image.Image:
    k = size / 1024
    img = Image.new("RGB", (size, size))
    d = ImageDraw.Draw(img)
    top, bot = (124, 77, 255), (30, 136, 229)
    for y in range(size):  # degradado diagonal aproximado
        t = y / (size - 1)
        d.line([(0, y), (size, y)], fill=tuple(int(a + (b - a) * t) for a, b in zip(top, bot)))
    white = (255, 255, 255)
    # cuerpo del mando
    d.rounded_rectangle([170 * k, 330 * k, 854 * k, 700 * k], radius=185 * k, fill=white)
    # empuñaduras
    d.ellipse([170 * k, 470 * k, 430 * k, 800 * k], fill=white)
    d.ellipse([594 * k, 470 * k, 854 * k, 800 * k], fill=white)
    ink = (88, 60, 220)
    # cruceta
    cx, cy, a, b = 345 * k, 500 * k, 34 * k, 100 * k
    d.rounded_rectangle([cx - a, cy - b, cx + a, cy + b], radius=12 * k, fill=ink)
    d.rounded_rectangle([cx - b, cy - a, cx + b, cy + a], radius=12 * k, fill=ink)
    # cuatro botones
    for dx, dy in [(0, -70), (70, 0), (0, 70), (-70, 0)]:
        x, y, r = 680 * k + dx * k, 500 * k + dy * k, 38 * k
        d.ellipse([x - r, y - r, x + r, y + r], fill=ink)
    return img


if __name__ == "__main__":
    size = int(sys.argv[2]) if len(sys.argv) > 2 else 1024
    icon(size).save(sys.argv[1])
