"""Autark icon concepts, drawn on the adaptive-icon grid: 108 units, the inner 72 visible, a 66 safe circle."""
import math
import os
import random
import subprocess
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(__file__))
from glyphs import CAP, contours, fmt, text_path  # noqa: E402

OUT = os.path.join(os.path.dirname(__file__), "build")
os.makedirs(OUT, exist_ok=True)

PAPER, PAPER2, INK, INK2, GREY = "#F5F5F3", "#EBEBE8", "#1C1C1B", "#3A3A39", "#8B8B88"
NIGHT, NIGHT_INK, NIGHT_GREY = "#141414", "#F2F2F0", "#7C7C79"
ORANGE = "#F0552E"


# ---------- geometry ----------

def lambda_points(weight, cap, cx, baseline):
    """Inter Tight's A without its crossbar, as a polygon; and where the crossbar sat."""
    rec = contours("A", weight)
    s = cap / CAP
    pts, bar = [], []
    which = 0
    for op, args in rec:
        if op == "moveTo":
            which += 1
        if op in ("moveTo", "lineTo"):
            (pts if which == 1 else bar).append(args[0])
    xs = [p[0] for p in pts]
    mid = (min(xs) + max(xs)) / 2
    tr = lambda p: (cx + (p[0] - mid) * s, baseline - p[1] * s)
    bar_y = [p[1] for p in bar]
    bar_mid = baseline - (min(bar_y) + max(bar_y)) / 2 * s
    bar_h = (max(bar_y) - min(bar_y)) * s
    return [tr(p) for p in pts], bar_mid, bar_h, s


def poly_d(pts):
    return "M" + "L".join(f"{fmt(x)} {fmt(y)}" for x, y in pts) + "Z"


def circle_d(cx, cy, r):
    return (f"M{fmt(cx - r)} {fmt(cy)}a{fmt(r)} {fmt(r)} 0 1 0 {fmt(2 * r)} 0"
            f"a{fmt(r)} {fmt(r)} 0 1 0 {fmt(-2 * r)} 0Z")


def inner_leg_distance(pts, cx, cy):
    """Perpendicular distance from (cx, cy) to the Λ's inner left edge (bottom-inner to counter apex)."""
    (x1, y1), (x2, y2) = pts[0], pts[7]
    dx, dy = x2 - x1, y2 - y1
    return abs((cx - x1) * dy - (cy - y1) * dx) / math.hypot(dx, dy)


# ---------- rendering ----------

def svg(body, view="0 0 108 108", size=108, defs=""):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{view}" width="{size}" height="{size}">'
            f"<defs>{defs}</defs>{body}</svg>")


def render(svg_text, path, px):
    src = path + ".svg"
    with open(src, "w") as f:
        f.write(svg_text)
    subprocess.run(["rsvg-convert", "-w", str(px), "-h", str(px), src, "-o", path], check=True)
    return path


MASKS = {
    "circle": "M54 18a36 36 0 1 1 0 72a36 36 0 1 1 0-72Z",
    # AOSP's squircle, scaled from its 100-unit box onto the 72-unit viewport.
    "squircle": None,
}


def squircle():
    k, o = 0.72, 18
    p = lambda x, y: f"{fmt(o + x * k)} {fmt(o + y * k)}"
    return (f"M{p(50, 0)}C{p(10, 0)} {p(0, 10)} {p(0, 50)}C{p(0, 90)} {p(10, 100)} {p(50, 100)}"
            f"C{p(90, 100)} {p(100, 90)} {p(100, 50)}C{p(100, 10)} {p(90, 0)} {p(50, 0)}Z")


MASKS["squircle"] = squircle()


def masked(layers, shape, px, name):
    defs = f'<clipPath id="m"><path d="{MASKS[shape]}"/></clipPath>'
    return render(svg(f'<g clip-path="url(#m)">{layers}</g>', "18 18 72 72", px, defs), f"{OUT}/{name}_{shape}_{px}.png", px)


def play(layers, name, px=512):
    """Google Play's 512 square: the visible 72 units, unmasked (Play rounds the corners itself)."""
    return render(svg(layers, "18 18 72 72", px), f"{OUT}/{name}_play_{px}.png", px)


def mask_png(shape_svg_body, res=1080):
    """Rasterises a white-on-black shape over the full 108 grid; returns a boolean array [y, x]."""
    tmp = f"{OUT}/_mask.png"
    render(svg(f'<rect width="108" height="108" fill="#000"/>{shape_svg_body}'), tmp, res)
    a = np.asarray(Image.open(tmp).convert("L"))
    return a > 127, res / 108


# ---------- stipple, after piyushbodaa.com's motion.js ----------

def stipple(mask, scale, gap, letter_density, field_density, rmin, rmax, light, seed=7, bounds=(0, 108)):
    rng = random.Random(seed)
    lo, hi = bounds
    dots = []
    y = lo + gap / 2
    while y < hi:
        x = lo + gap / 2
        while x < hi:
            jx = x + (rng.random() - 0.5) * gap * 0.9
            jy = y + (rng.random() - 0.5) * gap * 0.9
            ix = min(mask.shape[1] - 1, max(0, int(jx * scale)))
            iy = min(mask.shape[0] - 1, max(0, int(jy * scale)))
            far = min(1.0, math.hypot(jx - light[0], jy - light[1]) / (72 * 1.05))
            d = letter_density(far) if mask[iy, ix] else field_density(far)
            if rng.random() <= d:
                dots.append((jx, jy, gap * (rmin + rng.random() * (rmax - rmin))))
            x += gap
        y += gap
    return dots


def dots_d(dots):
    return "".join(circle_d(x, y, r) for x, y, r in dots)


# ---------- concepts ----------

def concept_core(weight=600, cap=46, lift=1.0, core_at=0.0, core_r=4.6, bg=NIGHT, fg=NIGHT_INK):
    """Λ: the A's legs in paper, the crossbar replaced by the orange core."""
    baseline = 54 + cap / 2 - lift
    pts, bar_mid, bar_h, s = lambda_points(weight, cap, 54, baseline)
    cy = bar_mid + core_at
    gap = inner_leg_distance(pts, 54, cy) - core_r
    body = (f'<rect width="108" height="108" fill="{bg}"/>'
            f'<path fill="{fg}" d="{poly_d(pts)}"/>'
            f'<path fill="{ORANGE}" d="{circle_d(54, cy, core_r)}"/>')
    mono = f'<path fill="#fff" d="{poly_d(pts)}{circle_d(54, cy, core_r)}"/>'
    return body, mono, {"clearance": round(gap, 2), "core_y": round(cy, 2), "bar_h": round(bar_h, 2)}


def concept_fold(weight=600, cap=46, lift=1.0, core_r=4.6, paper=True, lit="left", halo=False):
    """The Λ in the headline's two tones, split down its middle like a lit tent, the core inside."""
    baseline = 54 + cap / 2 - lift
    pts, bar_mid, _, _ = lambda_points(weight, cap, 54, baseline)
    cy = bar_mid
    bright, dim = (INK2, GREY) if paper else (NIGHT_INK, NIGHT_GREY)
    bg = PAPER if paper else NIGHT
    top, notch = pts[2][1], pts[7][1]
    left = [pts[1], pts[2], (54, top), (54, notch), pts[7], pts[0]]
    right = [(54, top), pts[3], pts[4], pts[5], pts[6], (54, notch)]
    lead, rest = (left, right) if lit == "left" else (right, left)
    body = f'<rect width="108" height="108" fill="{bg}"/>'
    if halo:
        body += (f'<circle cx="54" cy="{fmt(cy)}" r="{fmt(core_r + 2.4)}" fill="none" stroke="{ORANGE}" '
                 f'stroke-width="1.3" stroke-opacity=".35"/>')
    # The whole Λ in the quiet tone first, then the lit half over it, so no seam can show between them.
    body += (f'<path fill="{dim}" d="{poly_d(pts)}"/><path fill="{bright}" d="{poly_d(lead)}"/>'
             f'<path fill="{ORANGE}" d="{circle_d(54, cy, core_r)}"/>')
    mono = (f'<path fill="#fff" fill-opacity=".6" d="{poly_d(rest)}"/><path fill="#fff" d="{poly_d(lead)}"/>'
            f'<path fill="#fff" d="{circle_d(54, cy, core_r)}"/>')
    return body, mono, {"clearance": round(inner_leg_distance(pts, 54, cy) - core_r, 2)}


def concept_core_lit(weight=600, cap=46, lift=1.0, core_r=4.6, gap=1.45, seed=11):
    """Λ in paper dots, lit from the orange core inside it: dense near the core, thinning to the apex."""
    baseline = 54 + cap / 2 - lift
    pts, bar_mid, _, _ = lambda_points(weight, cap, 54, baseline)
    cy = bar_mid
    m, sc = mask_png(f'<path fill="#fff" d="{poly_d(pts)}"/>')
    dist = lambda far: far  # unused
    dots = []
    rng = random.Random(seed)
    y = 18 + gap / 2
    while y < 90:
        x = 18 + gap / 2
        while x < 90:
            jx = x + (rng.random() - 0.5) * gap * 0.9
            jy = y + (rng.random() - 0.5) * gap * 0.9
            if m[min(1079, int(jy * sc)), min(1079, int(jx * sc))]:
                near = max(0.0, 1 - math.hypot(jx - 54, jy - cy) / 30)
                if rng.random() <= 0.35 + 0.65 * near:
                    dots.append((jx, jy, gap * (0.3 + 0.16 * near + rng.random() * 0.1)))
            x += gap
        y += gap
    body = (f'<rect width="108" height="108" fill="{NIGHT}"/>'
            f'<path fill="{NIGHT_INK}" d="{dots_d(dots)}"/>'
            f'<path fill="{ORANGE}" d="{circle_d(54, cy, core_r)}"/>')
    mono = f'<path fill="#fff" d="{poly_d(pts)}{circle_d(54, cy, core_r)}"/>'
    return body, mono, {"dots": len(dots)}


def concept_stipple(weight=600, cap=44, gap=1.9, rmin=0.34, rmax=0.5, paper=True, dot=True, seed=5):
    """The site's stipple: a dotted field lit from the upper right, the A knocked out of it."""
    baseline = 54 + cap / 2
    a_path, width = text_path("A", weight, cap, 54, baseline, anchor="center")
    m, sc = mask_png(f'<path fill="#fff" d="{a_path}"/>')
    light = (18 + 72 * 0.75, 18 + 72 * 0.2)
    dots = stipple(m, sc, gap,
                   letter_density=lambda far: 0.03 + 0.62 * far * far,
                   field_density=lambda far: 0.6 + 0.4 * min(1, far * 1.3),
                   rmin=rmin, rmax=rmax, light=light, seed=seed)
    bg, ink = (PAPER, INK) if paper else (NIGHT, NIGHT_INK)
    body = f'<rect width="108" height="108" fill="{bg}"/><path fill="{ink}" d="{dots_d(dots)}"/>'
    if dot:
        body += f'<path fill="{ORANGE}" d="{circle_d(54 + width / 2 + 3.2, baseline - 2.6, 2.6)}"/>'
    mono = f'<path fill="#fff" d="{a_path}"/>'
    return body, mono, {"dots": len(dots)}


def concept_headline(paper=True, cap=15.5, weight=500, period=ORANGE):
    """AUT / ARK. — the site's two-tone headline, set as a block."""
    lead, rest = (INK2, GREY) if paper else (NIGHT_INK, NIGHT_GREY)
    bg = PAPER if paper else NIGHT
    em = cap / (CAP / 2048)
    pitch = em * 1.06
    top = 54 - (pitch + cap) / 2
    l1, w1 = text_path("AUT", weight, cap, 54, top + cap, -0.012, "center")
    l2, w2 = text_path("ARK", weight, cap, 54 - 0, top + cap + pitch, -0.012, "center")
    body = (f'<rect width="108" height="108" fill="{bg}"/>'
            f'<path fill="{lead}" d="{l1}"/><path fill="{rest}" d="{l2}"/>')
    if period:
        dot, _ = text_path(".", weight, cap, 54 + w2 / 2 - 0.2, top + cap + pitch, 0, "left")
        body += f'<path fill="{period}" d="{dot}"/>'
    mono = f'<path fill="#fff" d="{l1}"/><path fill="#fff" fill-opacity=".55" d="{l2}"/>'
    return body, mono, {"w1": round(w1, 1), "w2": round(w2, 1)}


def current():
    return (f'<rect width="108" height="108" fill="{NIGHT}"/>'
            '<path fill="#F2F2F0" d="M41.64 69H35.83L46.42 39H53.11L63.67 69H57.88L49.87 45.15H49.63ZM41.84 57.23H57.65V61.6H41.84Z"/>'
            f'<path fill="{ORANGE}" d="M66.17,66a3,3 0,1 1,6 0a3,3 0,1 1,-6 0z"/>'), "", {}


CONCEPTS = {
    "current": current,
    "core": concept_core,
    "core_low": lambda: concept_core(core_at=3.0, core_r=5.0),
    "core_paper": lambda: concept_core(bg=PAPER, fg=INK2),
    "core_lit": concept_core_lit,
    "stipple_paper": concept_stipple,
    "stipple_night": lambda: concept_stipple(paper=False),
    "headline_paper": concept_headline,
    "headline_night": lambda: concept_headline(paper=False),
    "fold_paper": concept_fold,
    "fold_paper_r": lambda: concept_fold(lit="right"),
    "fold_night": lambda: concept_fold(paper=False),
    "fold_night_r": lambda: concept_fold(paper=False, lit="right"),
    "fold_night_halo": lambda: concept_fold(paper=False, halo=True),
    "fold_night_650": lambda: concept_fold(paper=False, weight=650, core_r=4.8),
}


def sheet(names, path):
    """Each concept at 512 (Play), 192 circle, 192 squircle, and true launcher size, on paper and night."""
    cols = [("play", 256), ("circle", 160), ("squircle", 160), ("circle", 63)]
    row_h, pad = 290, 24
    W = pad + sum(c[1] + pad for c in cols) + 2 * (63 + pad)
    img = Image.new("RGB", (W, len(names) * row_h + pad), "#d8d8d4")
    for i, n in enumerate(names):
        body, _, _ = CONCEPTS[n]()
        x = pad
        y = pad + i * row_h
        for kind, px in cols:
            f = play(body, n, 512) if kind == "play" else masked(body, kind, px * 2, n)
            im = Image.open(f).convert("RGBA").resize((px, px), Image.LANCZOS)
            if kind == "play":
                r = Image.new("L", (px, px), 0)
                from PIL import ImageDraw
                ImageDraw.Draw(r).rounded_rectangle((0, 0, px - 1, px - 1), radius=int(px * 0.2), fill=255)
                im.putalpha(r)
            img.paste(im, (x, y + (256 - px) // 2), im)
            x += px + pad
        for wall in ("#20252e", "#c9d3df"):
            tile = Image.new("RGB", (63 + pad, 63 + 2 * pad), wall)
            im = Image.open(f"{OUT}/{n}_circle_126.png").convert("RGBA").resize((63, 63), Image.LANCZOS)
            tile.paste(im, (pad // 2, pad), im)
            img.paste(tile, (x, y + (256 - 63) // 2 - pad))
            x += 63 + pad
    img.save(path)


if __name__ == "__main__":
    names = sys.argv[1:] or list(CONCEPTS)
    for n in names:
        print(n, CONCEPTS[n]()[2])
    sheet(names, f"{OUT}/sheet.png")
    print("sheet", f"{OUT}/sheet.png")
