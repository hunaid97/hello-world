# HN_FRAME graphics test

Shows a set of Figma frames on the 160x80 display (landscape) and scrolls through them with
the knob. Same wiring as the picture frame (`../README.md`).

- Turn the knob: next / previous frame, wrapping around, with an LED blip (no sound).
  First every frame landscape (160x80), then every frame again upright for the screen held
  portrait (80x160, fitted to the width, at the top).
- Press the knob: turn the screen 180 degrees.

## Making the images

The frames aren't in git (they're design work). To rebuild them:

1. Export each Figma frame as PNG into `figma/`, named so they sort in order (`01.png`, `02.png`, ...).
2. `python3 make_graphics.py` (needs Pillow): turns the grey canvas behind the rounded
   corners black, fits each frame into 160x80 without distorting it,
   centred on black, and writes `graphics_test/graphics.h`.
3. Flash `graphics_test/` as usual. It's a separate sketch, so the picture frame's photos stay
   in flash.
