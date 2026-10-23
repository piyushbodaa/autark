# Design

Autark's launcher icon is Inter Tight SemiBold's capital A, the face of the AUTARK wordmark, with its crossbar taken
out and the app's orange core put where it was. The legs take a piyushbodaa.com headline's two tones, lit and quiet,
on night.

The design board (mark, phone screens, construction, sizes, other directions) is `board/`, deployed to the `autark`
project on Vercel: https://autark-piyushbodaa.vercel.app (behind Vercel login).

## Files

- `icon/play-icon-512.png`: the Play Store icon, 512 × 512, 32-bit PNG. `icon/play-icon-512.svg` is its source.
- `tools/android.py`: writes the adaptive-icon layers (`ic_launcher_foreground.xml`, `ic_launcher_monochrome.xml`)
  and the Play icon.
- `tools/gen.py`: the concepts that were tried (stipple, headline, one tone, paper), drawn side by side into
  `tools/build/sheet.png`.
- `tools/glyphs.py`: Inter Tight outlines from `app/src/main/res/font/inter_tight.ttf`, shaped with HarfBuzz.

## Colours

| Name       | Hex       | Where                                  |
|------------|-----------|----------------------------------------|
| Night      | `#141414` | background, `@color/night`             |
| Night ink  | `#F2F2F0` | lit leg, `@color/night_ink`            |
| Night grey | `#7C7C79` | quiet leg, `@color/night_grey`         |
| Orange     | `#F0552E` | core, `@color/accent`                  |

## Regenerating

Needs `rsvg-convert` and ImageMagick.

```sh
python -m venv .venv && .venv/bin/pip install fonttools uharfbuzz pillow numpy
.venv/bin/python design/tools/android.py app/src/main/res night design/icon/play-icon-512.png
```
