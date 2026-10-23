"""Inter Tight outlines at any weight, shaped with HarfBuzz, emitted as SVG path data (y down)."""
import os
from functools import lru_cache

import uharfbuzz as hb
from fontTools.pens.recordingPen import RecordingPen
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont

FONT = os.path.join(os.path.dirname(__file__), "../../app/src/main/res/font/inter_tight.ttf")
UPM = 2048
CAP = 1490


@lru_cache(None)
def static(weight):
    return instantiateVariableFont(TTFont(FONT), {"wght": weight})


@lru_cache(None)
def _hb(weight):
    font = hb.Font(hb.Face(hb.Blob.from_file_path(FONT)))
    font.set_variations({"wght": weight})
    return font


def shape(text, weight, tracking_em=0.0):
    """[(glyph_name, x_in_font_units)] and the run's advance width in font units."""
    buf = hb.Buffer()
    buf.add_str(text)
    buf.guess_segment_properties()
    hb.shape(_hb(weight), buf, {"kern": True, "liga": True})
    order = static(weight).getGlyphOrder()
    out, x = [], 0
    for info, pos in zip(buf.glyph_infos, buf.glyph_positions):
        out.append((order[info.codepoint], x + pos.x_offset))
        x += pos.x_advance + tracking_em * UPM
    return out, x - tracking_em * UPM


def fmt(v):
    return f"{v:.2f}".rstrip("0").rstrip(".")


def text_path(text, weight, cap_height, x, baseline, tracking_em=0.0, anchor="left"):
    """SVG path for text whose capitals are cap_height tall, sitting on baseline (y grows down)."""
    s = cap_height / CAP
    glyphs, width = shape(text, weight, tracking_em)
    if anchor == "center":
        x -= width * s / 2
    gs = static(weight).getGlyphSet()
    pen = SVGPathPen(gs, fmt)
    for name, gx in glyphs:
        gs[name].draw(TransformPen(pen, (s, 0, 0, -s, x + gx * s, baseline)))
    return pen.getCommands(), width * s


def ink_bounds(text, weight):
    """Left and right edges of the drawn outlines, in font units, relative to the run's origin."""
    from fontTools.pens.boundsPen import BoundsPen
    glyphs, _ = shape(text, weight)
    gs = static(weight).getGlyphSet()
    lo, hi = None, None
    for name, gx in glyphs:
        bp = BoundsPen(gs)
        gs[name].draw(bp)
        if bp.bounds:
            x0, _, x1, _ = bp.bounds
            lo = gx + x0 if lo is None else min(lo, gx + x0)
            hi = gx + x1 if hi is None else max(hi, gx + x1)
    return lo, hi


def contours(char, weight):
    rec = RecordingPen()
    f = static(weight)
    f.getGlyphSet()[f.getBestCmap()[ord(char)]].draw(rec)
    return rec.value
