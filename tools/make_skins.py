#!/usr/bin/env python3
"""Genera las skins originales "Classic" de DeltaDroid / DeltaVita en formato .deltaskin (info.json + PNG).

Son diseño propio (no derivan de las de Delta, que no tienen licencia para redistribuirse), con el mismo
formato para que el resto del código y las skins importadas funcionen igual.

Uso: make_skins.py <salida_android> [salida_vita]
  - Android: representaciones iphone/standard y edgeToEdge, imágenes a 3x.
  - Vita: solo iphone/standard, imágenes al tamaño exacto de la pantalla (960x544 / 544x960).
"""
import io
import json
import os
import sys
import zipfile

from PIL import Image, ImageDraw, ImageFont

FONT = "/System/Library/Fonts/Helvetica.ttc"
ACCENT = {"nes": (229, 57, 53), "snes": (149, 117, 205), "gbc": (142, 36, 170), "gba": (124, 77, 255),
          "n64": (67, 160, 71), "ds": (144, 164, 174), "genesis": (30, 136, 229)}
NAMES = {"nes": "NES", "snes": "SNES", "gbc": "Game Boy Color", "gba": "Game Boy Advance",
         "n64": "Nintendo 64", "ds": "Nintendo DS", "genesis": "Genesis"}
IDS = {"nes": "nes", "snes": "snes", "gbc": "gbc", "gba": "gba", "n64": "n64", "ds": "ds", "genesis": "genesis"}

# ---------- disposición (coordenadas de mapeo; ax = ancla horizontal: l, c o r) ----------
# cada elemento: (tipo, nombre/inputs, x, y, w, h, ancla)


def shoulders(y=12, w=104, h=38):
    return [("pill", ["l"], 14, y, w, h, "l"), ("pill", ["r"], -14 - w, y, w, h, "r")]


def landscape_layout(sys_id):
    W = 667
    it = []
    if sys_id == "n64":
        it += [("stick", None, 40, 100, 100, 100, "l"), ("dpad", None, 45, 228, 110, 110, "l"),
               ("pill", ["z"], 14, 12, 90, 36, "l"), ("pill", ["l"], 112, 12, 80, 36, "l"), ("pill", ["r"], -14 - 104, 12, 104, 36, "r"),
               ("round", ["a"], -128, 268, 66, 66, "r"), ("round", ["b"], -200, 232, 60, 60, "r")]
        cx, cy, s = -92, 150, 40
        it += [("round", ["cUp"], cx - s / 2, cy - s - 6, s, s, "r"), ("round", ["cDown"], cx - s / 2, cy + 6, s, s, "r"),
               ("round", ["cLeft"], cx - s - 22, cy - s / 2, s, s, "r"), ("round", ["cRight"], cx + 22, cy - s / 2, s, s, "r")]
        it += [("pill", ["start"], W / 2 - 36, 330, 72, 28, "c")]
    else:
        it += [("dpad", None, 22, 186, 152, 152, "l")]
        if sys_id in ("nes", "gbc", "gba"):
            it += [("round", ["b"], -180, 262, 76, 76, "r"), ("round", ["a"], -96, 214, 76, 76, "r")]
        elif sys_id == "genesis":
            for i, n in enumerate(["a", "b", "c"]):
                it.append(("round", [n], -232 + i * 74, 274, 62, 62, "r"))
            for i, n in enumerate(["x", "y", "z"]):
                it.append(("round", [n], -232 + i * 74 + 12, 200, 50, 50, "r"))
        else:  # snes, ds: rombo de cuatro botones
            cx, cy, s = -108, 262, 60
            it += [("round", ["x"], cx - s / 2, cy - s - 14, s, s, "r"), ("round", ["b"], cx - s / 2, cy + 14, s, s, "r"),
                   ("round", ["y"], cx - s - 36, cy - s / 2, s, s, "r"), ("round", ["a"], cx + 36, cy - s / 2, s, s, "r")]
        if sys_id != "nes" and sys_id != "gbc":
            it += shoulders()
        it += [("pill", ["select"], W / 2 - 84, 334, 72, 26, "c"), ("pill", ["start"], W / 2 + 12, 334, 72, 26, "c")]
    it += [("pill", ["menu"], W / 2 - 36, 12, 72, 26, "c")]
    return it


def portrait_layout(sys_id):
    """Control debajo del juego: 375 de ancho; devuelve (alto, elementos)."""
    W = 375
    if sys_id == "n64":
        H = 330
        it = [("stick", None, 30, 56, 96, 96, "l"), ("dpad", None, 34, 178, 104, 104, "l"),
              ("pill", ["z"], 14, 12, 74, 30, "l"), ("pill", ["l"], 96, 12, 66, 30, "l"), ("pill", ["r"], -14 - 90, 12, 90, 30, "r"),
              ("round", ["a"], -120, 226, 62, 62, "r"), ("round", ["b"], -186, 194, 54, 54, "r")]
        cx, cy, s = -84, 118, 36
        it += [("round", ["cUp"], cx - s / 2, cy - s - 4, s, s, "r"), ("round", ["cDown"], cx - s / 2, cy + 4, s, s, "r"),
               ("round", ["cLeft"], cx - s - 20, cy - s / 2, s, s, "r"), ("round", ["cRight"], cx + 20, cy - s / 2, s, s, "r")]
        it += [("pill", ["start"], W / 2 - 34, 292, 68, 26, "c"), ("pill", ["menu"], 14, 296, 58, 22, "l")]
        return H, it
    H = 290
    it = [("dpad", None, 18, 72, 140, 140, "l")]
    if sys_id in ("nes", "gbc", "gba"):
        it += [("round", ["b"], -160, 140, 70, 70, "r"), ("round", ["a"], -84, 96, 70, 70, "r")]
    elif sys_id == "genesis":
        for i, n in enumerate(["a", "b", "c"]):
            it.append(("round", [n], -200 + i * 64, 150, 56, 56, "r"))
        for i, n in enumerate(["x", "y", "z"]):
            it.append(("round", [n], -200 + i * 64 + 10, 84, 46, 46, "r"))
    else:
        cx, cy, s = -96, 142, 54
        it += [("round", ["x"], cx - s / 2, cy - s - 12, s, s, "r"), ("round", ["b"], cx - s / 2, cy + 12, s, s, "r"),
               ("round", ["y"], cx - s - 30, cy - s / 2, s, s, "r"), ("round", ["a"], cx + 30, cy - s / 2, s, s, "r")]
    if sys_id not in ("nes", "gbc"):
        it += shoulders(12, 96, 32)
    it += [("pill", ["select"], W / 2 - 76, 248, 64, 24, "c"), ("pill", ["start"], W / 2 + 12, 248, 64, 24, "c"),
           ("pill", ["menu"], 14, 250, 58, 22, "l")]
    return H, it


def ds_layout(ori):
    """DS: pantallas incluidas en la skin (táctil en la de abajo / derecha)."""
    if ori == "portrait":
        W, H = 375, 667
        top, bot = (10, 8, 355, 266), (10, 282, 355, 266)
        y0 = 556
        it = [("dpad", None, 16, y0, 104, 104, "l"), ("pill", ["l"], 136, y0, 46, 26, "l"), ("pill", ["r"], -136 - 46, y0, 46, 26, "r"),
              ("pill", ["select"], W / 2 - 62, y0 + 72, 56, 22, "c"), ("pill", ["start"], W / 2 + 6, y0 + 72, 56, 22, "c"),
              ("pill", ["menu"], W / 2 - 28, y0 + 36, 56, 22, "c")]
        cx, cy, s = -66, y0 + 52, 36
    else:
        W, H = 667, 375
        top, bot = (24, 14, 296, 222), (347, 14, 296, 222)
        y0 = 250
        it = [("dpad", None, 24, y0, 116, 116, "l"), ("pill", ["l"], 160, y0, 80, 28, "l"), ("pill", ["r"], -160 - 80, y0, 80, 28, "r"),
              ("pill", ["select"], W / 2 - 80, y0 + 82, 72, 26, "c"), ("pill", ["start"], W / 2 + 8, y0 + 82, 72, 26, "c"),
              ("pill", ["menu"], W / 2 - 36, y0 + 40, 72, 26, "c")]
        cx, cy, s = -84, y0 + 58, 44
    it += [("round", ["x"], cx - s / 2, cy - s - 8, s, s, "r"), ("round", ["b"], cx - s / 2, cy + 8, s, s, "r"),
           ("round", ["y"], cx - s - 24, cy - s / 2, s, s, "r"), ("round", ["a"], cx + 24, cy - s / 2, s, s, "r")]
    it.append(("touch", None, *bot, "l"))
    return W, H, top, bot, it


# ---------- dibujo ----------


def font(px):
    return ImageFont.truetype(FONT, max(8, int(px)))


def place(el, W):
    kind, names, x, y, w, h, ax = el
    if ax == "r":
        x = W + x
    elif ax == "c":
        x = x + (W - 667) / 2 if W != 375 else x  # los "c" ya vienen centrados para su ancho base
    return kind, names, x, y, w, h


def draw_skin(sys_id, W, H, items, k, translucent, screens=None):
    img = Image.new("RGBA", (round(W * k), round(H * k)), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    acc = ACCENT[sys_id]
    if not translucent:
        d.rectangle([0, 0, img.width, img.height], fill=(24, 24, 29, 255))
        if screens is None:
            d.rectangle([0, 0, img.width, 4 * k], fill=acc + (255,))
    for (sx, sy, sw, sh) in screens or []:
        d.rectangle([sx * k, sy * k, (sx + sw) * k, (sy + sh) * k], fill=(0, 0, 0, 255))
    btn = (44, 44, 52, 235) if not translucent else (255, 255, 255, 70)
    line = (255, 255, 255, 60) if not translucent else (255, 255, 255, 150)
    txt = (235, 235, 242, 255)
    out = []
    for el in items:
        kind, names, x, y, w, h = place(el, W)
        X, Y, Wd, Hd = x * k, y * k, w * k, h * k
        entry = {"frame": {"x": round(x, 1), "y": round(y, 1), "width": w, "height": h}}
        if kind == "dpad":
            t = Wd / 3
            for rx, ry, rw, rh in [(X + t, Y, t, Hd), (X, Y + t, Wd, t)]:
                d.rounded_rectangle([rx, ry, rx + rw, ry + rh], radius=t * 0.25, fill=btn, outline=line, width=max(1, int(k)))
            for cx, cy, pts in [(X + Wd / 2, Y + t / 2, "u"), (X + Wd / 2, Y + Hd - t / 2, "d"), (X + t / 2, Y + Hd / 2, "l"), (X + Wd - t / 2, Y + Hd / 2, "r")]:
                a = t * 0.22
                tri = {"u": [(cx, cy - a), (cx - a, cy + a * 0.7), (cx + a, cy + a * 0.7)],
                       "d": [(cx, cy + a), (cx - a, cy - a * 0.7), (cx + a, cy - a * 0.7)],
                       "l": [(cx - a, cy), (cx + a * 0.7, cy - a), (cx + a * 0.7, cy + a)],
                       "r": [(cx + a, cy), (cx - a * 0.7, cy - a), (cx - a * 0.7, cy + a)]}[pts]
                d.polygon(tri, fill=txt)
            entry["inputs"] = {"up": "up", "down": "down", "left": "left", "right": "right"}
            entry["extendedEdges"] = {"top": 14, "bottom": 14, "left": 14, "right": 14}
        elif kind == "stick":
            d.ellipse([X, Y, X + Wd, Y + Hd], fill=btn, outline=line, width=max(1, int(k)))
            entry["inputs"] = {"up": "analogStickUp", "down": "analogStickDown", "left": "analogStickLeft", "right": "analogStickRight"}
            entry["thumbstick"] = {"name": "thumbstick.png", "width": w * 0.62, "height": h * 0.62}
        elif kind == "touch":
            entry["inputs"] = {"x": "touchScreenX", "y": "touchScreenY"}
        else:
            label = names[0]
            disp = {"select": "SELECT", "start": "START", "menu": "MENU"}.get(label, label.upper())
            face = label in ("a", "b", "c", "x", "y", "z")
            fill = (acc + (235,)) if (face and not translucent) else (acc + (150,)) if face else btn
            if label.startswith("c") and len(label) > 1:
                fill = (230, 190, 40, 235) if not translucent else (230, 190, 40, 150)
            if kind == "round":
                d.ellipse([X, Y, X + Wd, Y + Hd], fill=fill, outline=line, width=max(1, int(k)))
            else:
                d.rounded_rectangle([X, Y, X + Wd, Y + Hd], radius=Hd / 2, fill=btn, outline=line, width=max(1, int(k)))
            if label in ("cUp", "cDown", "cLeft", "cRight"):  # flecha dibujada: la fuente no trae ▲◀
                cx, cy, r = X + Wd / 2, Y + Hd / 2, Wd * 0.22
                tri = {"cUp": [(cx, cy - r), (cx - r, cy + r * 0.7), (cx + r, cy + r * 0.7)],
                       "cDown": [(cx, cy + r), (cx - r, cy - r * 0.7), (cx + r, cy - r * 0.7)],
                       "cLeft": [(cx - r, cy), (cx + r * 0.7, cy - r), (cx + r * 0.7, cy + r)],
                       "cRight": [(cx + r, cy), (cx - r * 0.7, cy - r), (cx - r * 0.7, cy + r)]}[label]
                d.polygon(tri, fill=(40, 34, 10, 255))
            else:
                f = font((Hd * 0.42) if kind == "round" else (Hd * 0.46 if len(disp) > 2 else Hd * 0.6))
                tb = d.textbbox((0, 0), disp, font=f)
                d.text((X + (Wd - (tb[2] - tb[0])) / 2 - tb[0], Y + (Hd - (tb[3] - tb[1])) / 2 - tb[1]), disp, font=f, fill=txt)
            entry["inputs"] = names
            entry["extendedEdges"] = {"top": 6, "bottom": 6, "left": 6, "right": 6}
        out.append(entry)
    return img, out


def thumb_png(k):
    s = round(60 * k)
    img = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    ImageDraw.Draw(img).ellipse([0, 0, s - 1, s - 1], fill=(235, 235, 242, 230), outline=(0, 0, 0, 90), width=max(1, int(k)))
    return img


def png_bytes(img):
    b = io.BytesIO()
    img.save(b, "PNG", optimize=True)
    return b.getvalue()


def representation(sys_id, ori, edge, k_for):
    """Devuelve (rep_json, {archivo: imagen}) para una orientación."""
    files = {}
    if sys_id == "ds":
        W, H, top, bot, items = ds_layout(ori)
        if edge and ori == "landscape":
            W = 812
        k = k_for(W, H)
        img, out = draw_skin(sys_id, W, H, items, k, False, [top, bot])
        rep = {"mappingSize": {"width": W, "height": H},
               "screens": [{"inputFrame": {"x": 0, "y": 0, "width": 256, "height": 192}, "outputFrame": dict(zip(["x", "y", "width", "height"], top))},
                           {"inputFrame": {"x": 0, "y": 192, "width": 256, "height": 192}, "outputFrame": dict(zip(["x", "y", "width", "height"], bot))}]}
    elif ori == "portrait":
        H, items = portrait_layout(sys_id)
        W = 375
        k = k_for(W, H)
        img, out = draw_skin(sys_id, W, H, items, k, False)
        rep = {"mappingSize": {"width": W, "height": H}}
    else:
        W, H = (812 if edge else 667), 375
        k = k_for(W, H)
        img, out = draw_skin(sys_id, W, H, landscape_layout(sys_id), k, True)
        rep = {"mappingSize": {"width": W, "height": H}, "translucent": True}
    name = f"{'e2e_' if edge else ''}{ori}.png"
    files[name] = img
    rep["assets"] = {"large": name}
    rep["items"] = out
    rep["extendedEdges"] = {"top": 6, "bottom": 6, "left": 6, "right": 6}
    if any("thumbstick" in i for i in out):
        files["thumbstick.png"] = thumb_png(k)
    return rep, files


def build(sys_id, path, vita):
    info = {"name": f"Classic {NAMES[sys_id]}", "identifier": f"com.deltadroid.classic.{sys_id}",
            "gameTypeIdentifier": f"com.rileytestut.delta.game.{IDS[sys_id]}", "debug": False}
    files = {}
    dev = {}
    # DS trae sus pantallas dentro de la skin: con la versión estándar basta (Android cae a "standard")
    variants = [("standard", False)] if vita or sys_id == "ds" else [("standard", False), ("edgeToEdge", True)]
    for key, edge in variants:
        dev[key] = {}
        for ori in ("portrait", "landscape"):
            if vita:
                view = (960, 544) if ori == "landscape" else (544, 960)
                k_for = lambda W, H, v=view: min(v[0] / W, v[1] / H)
            else:
                k_for = lambda W, H: 3.0
            rep, f = representation(sys_id, ori, edge, k_for)
            dev[key][ori] = rep
            files.update(f)
    info["representations"] = {"iphone": dev}
    with zipfile.ZipFile(path, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("info.json", json.dumps(info, ensure_ascii=False, indent=1))
        for n, img in files.items():
            z.writestr(n, png_bytes(img))


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit(__doc__)
    for i, out_dir in enumerate(sys.argv[1:3]):
        os.makedirs(out_dir, exist_ok=True)
        for s in ACCENT:
            build(s, os.path.join(out_dir, f"{s}.deltaskin"), vita=(i == 1))
            print(f"{'vita' if i else 'android'}: {s}")
