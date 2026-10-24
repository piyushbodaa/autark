"""Draws the Play Store feature graphic, 1024 × 500: the mark beside the setup screen's headline."""
import os
import re
import subprocess
import sys

sys.path.insert(0, os.path.dirname(__file__))
from android import HEX, geometry  # noqa: E402
from glyphs import text_path  # noqa: E402

W, H = 1024, 500
MARK_H = 200   # px from apex to feet
CAP = 44       # px, the headline's capitals
LEADING = 65   # px between baselines: the app's headline, 30 on 28
GAP = 60       # px between the mark and the headline
TRACKING = -0.012
LINES = [("A PRIVATE AI", "night_ink"), ("THAT LIVES ON", "night_grey"), ("YOUR PHONE.", "night_grey")]


def bounds(*paths):
    nums = [float(n) for d in paths for n in re.findall(r"-?\d+(?:\.\d+)?", re.sub(r"a[^Z]*", "", d))]
    xs, ys = nums[0::2], nums[1::2]
    return min(xs), min(ys), max(xs), max(ys)


def main(out):
    lit, quiet, core = geometry()
    x0, y0, x1, y1 = bounds(lit, quiet)
    s = MARK_H / (y1 - y0)
    mark_w = (x1 - x0) * s

    widths = [text_path(t, 500, CAP, 0, 0, TRACKING)[1] for t, _ in LINES]
    left = (W - (mark_w + GAP + max(widths))) / 2
    last = H / 2 + (CAP + LEADING * (len(LINES) - 1)) / 2  # the block of capitals sits on the centre line
    text_x = left + mark_w + GAP

    body = [f'<rect width="{W}" height="{H}" fill="{HEX["night"]}"/>',
            f'<g transform="translate({left - x0 * s:.2f} {last - y1 * s:.2f}) scale({s:.4f})">'
            f'<path fill="{HEX["night_grey"]}" d="{quiet}"/><path fill="{HEX["night_ink"]}" d="{lit}"/>'
            f'<path fill="{HEX["accent"]}" d="{core}"/></g>']
    for i, (text, colour) in enumerate(LINES):
        d, _ = text_path(text, 500, CAP, text_x, last - LEADING * (len(LINES) - 1 - i), TRACKING)
        body.append(f'<path fill="{HEX[colour]}" d="{d}"/>')

    src = os.path.splitext(out)[0] + ".svg"
    with open(src, "w") as f:
        f.write(f'<svg xmlns="http://www.w3.org/2000/svg" width="{W}" height="{H}" viewBox="0 0 {W} {H}">'
                + "".join(body) + "</svg>")
    subprocess.run(["rsvg-convert", "-w", str(W), "-h", str(H), src, "-o", out], check=True)
    # Play wants a 24-bit PNG or a JPEG: no alpha channel.
    subprocess.run(["magick", out, "-alpha", "off", "-define", "png:color-type=2", out], check=True)


if __name__ == "__main__":
    # python design/tools/feature.py design/store/feature-graphic.png
    main(sys.argv[1])
