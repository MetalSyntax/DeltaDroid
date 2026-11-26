#!/usr/bin/env python3
"""Ícono de DeltaDroid / DeltaVita: δ minúscula blanca y redondeada sobre degradado violeta (tono Delta).
Dibujado aquí, no copia el logo de Delta. Uso: make_icon.py salida.png [tamaño]"""
import sys
from PIL import Image, ImageDraw, ImageFilter, ImageFont

# SF Rounded en macOS; respaldo Helvetica negrita si no existe
FONTS = [("/System/Library/Fonts/SFNSRounded.ttf", 0), ("/System/Library/Fonts/Helvetica.ttc", 1)]
TOP, BOT = (184, 128, 255), (104, 46, 226)  # lavanda claro arriba-izquierda -> violeta profundo


def glyph_font(px):
    for path, idx in FONTS:
        try:
            f = ImageFont.truetype(path, px, index=idx)
        except OSError:
            continue
        try:
            f.set_variation_by_axes([800])  # Heavy
        except Exception:
            pass
        return f
    raise SystemExit("sin fuente con δ")


def icon(size: int) -> Image.Image:
    s = 1024  # se dibuja a 1024 y se escala: bordes limpios en cualquier tamaño
    gv = Image.linear_gradient("L").resize((s, s))
    grad = Image.blend(gv, gv.transpose(Image.ROTATE_90), 0.5)  # diagonal (x+y)/2
    img = Image.composite(Image.new("RGB", (s, s), BOT), Image.new("RGB", (s, s), TOP), grad)

    f = glyph_font(720)
    mask = Image.new("L", (s, s))
    ImageDraw.Draw(mask).text((s / 2, s / 2 + 20), "δ", font=f, fill=255, anchor="mm")
    shadow = mask.filter(ImageFilter.GaussianBlur(28)).point(lambda v: v * 0.45)
    img.paste((60, 20, 150), (0, 24), shadow)  # sombra suave hacia abajo
    img.paste((255, 255, 255), (0, 0), mask)
    return img.resize((size, size), Image.LANCZOS) if size != s else img


if __name__ == "__main__":
    size = int(sys.argv[2]) if len(sys.argv) > 2 else 1024
    icon(size).save(sys.argv[1])
