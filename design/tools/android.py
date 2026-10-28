"""Writes the lit-tent mark as Android adaptive-icon layers, plus the 512 Play Store icon."""
import os
import subprocess
import sys

sys.path.insert(0, os.path.dirname(__file__))
from gen import ORANGE, circle_d, fmt, lambda_points, poly_d, svg  # noqa: E402

WEIGHT, CAP, LIFT, CORE_R = 600, 46, 1.0, 4.6
SEAM_OVERLAP = 0.5  # the quiet half reaches under the lit one, so the split never shows a hairline

COLOURWAYS = {
    # background, lit half, quiet half: the two tones of a headline on piyushbodaa.com
    "night": ("night", "night_ink", "night_grey"),
    "paper": ("paper", "ink_2", "grey"),
}
HEX = {"night": "#141414", "night_ink": "#F2F2F0", "night_grey": "#7C7C79",
       "paper": "#F5F5F3", "ink_2": "#3A3A39", "grey": "#8B8B88", "accent": ORANGE}


def geometry():
    baseline = 54 + CAP / 2 - LIFT
    pts, bar_mid, _, _ = lambda_points(WEIGHT, CAP, 54, baseline)
    top, notch = pts[2][1], pts[7][1]
    lit = [pts[1], pts[2], (54, top), (54, notch), pts[7], pts[0]]
    quiet = [(54 - SEAM_OVERLAP, top), pts[3], pts[4], pts[5], pts[6], (54 - SEAM_OVERLAP, notch)]
    return poly_d(lit), poly_d(quiet), circle_d(54, bar_mid, CORE_R)


def vector(paths, comment):
    body = "\n".join(
        f'    <path\n        android:fillColor="{c}"{a}\n        android:pathData="{d}" />' for c, d, a in paths)
    return f"""<?xml version="1.0" encoding="utf-8"?>
<!-- {comment} -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
{body}
</vector>
"""


def write(res, colourway):
    lit, quiet, core = geometry()
    bg, lit_c, quiet_c = COLOURWAYS[colourway]
    fg = vector(
        [(f"@color/{quiet_c}", quiet, ""), (f"@color/{lit_c}", lit, ""), ("@color/accent", core, "")],
        "Inter Tight SemiBold's A without its crossbar, split into a headline's two tones; "
        "the orange core sits where the crossbar was.",
    )
    mono = vector(
        [("#FFFFFFFF", quiet, '\n        android:fillAlpha="0.55"'), ("#FFFFFFFF", lit + core, "")],
        "The same mark for themed icons; the quiet half stays quieter.",
    )
    os.makedirs(f"{res}/drawable", exist_ok=True)
    with open(f"{res}/drawable/ic_launcher_foreground.xml", "w") as f:
        f.write(fg)
    with open(f"{res}/drawable/ic_launcher_monochrome.xml", "w") as f:
        f.write(mono)
    return bg


def play_png(colourway, path, px=512):
    lit, quiet, core = geometry()
    bg, lit_c, quiet_c = COLOURWAYS[colourway]
    body = (f'<rect width="108" height="108" fill="{HEX[bg]}"/><path fill="{HEX[quiet_c]}" d="{quiet}"/>'
            f'<path fill="{HEX[lit_c]}" d="{lit}"/><path fill="{ORANGE}" d="{core}"/>')
    src = os.path.splitext(path)[0] + ".svg"
    with open(src, "w") as f:
        f.write(svg(body, "18 18 72 72", px))
    subprocess.run(["rsvg-convert", "-w", str(px), "-h", str(px), src, "-o", path], check=True)
    # Play wants 32-bit PNG; rsvg-convert drops the alpha channel of an opaque image.
    subprocess.run(["magick", path, "-alpha", "on", "-define", "png:color-type=6", path], check=True)


if __name__ == "__main__":
    # python design/tools/android.py app/src/main/res night design/icon/play-icon-512.png
    res, colourway = sys.argv[1], sys.argv[2]
    print(write(res, colourway))
    if len(sys.argv) > 3:
        play_png(colourway, sys.argv[3])
