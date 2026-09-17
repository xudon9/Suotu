#!/usr/bin/env python3
"""
Check the colour picker's HSV <-> RGB conversion and transparency handling.

A picker that returns a slightly wrong colour is worse than an obviously broken one:
the user sees the swatch they chose, gets something else in the image, and has no way to
tell which stage lied. So the conversion is verified against known values and for
round-trip stability.
"""


def rgb_to_hsv(argb):
    """Mirror of ColorPicker.kt rgbToHsv."""
    r = ((argb >> 16) & 0xFF) / 255.0
    g = ((argb >> 8) & 0xFF) / 255.0
    b = (argb & 0xFF) / 255.0
    mx, mn = max(r, g, b), min(r, g, b)
    d = mx - mn
    if d == 0:
        h = 0.0
    elif mx == r:
        h = 60.0 * (((g - b) / d) % 6)
    elif mx == g:
        h = 60.0 * (((b - r) / d) + 2)
    else:
        h = 60.0 * (((r - g) / d) + 4)
    if h < 0:
        h += 360
    return [h, 0.0 if mx == 0 else d / mx, mx]


def hsv_to_rgb(hue, sat, value, alpha):
    """Mirror of ColorPicker.kt hsvToRgb."""
    h = ((hue % 360) + 360) % 360
    c = value * sat
    x = c * (1 - abs((h / 60.0) % 2 - 1))
    m = value - c
    if h < 60:
        r1, g1, b1 = c, x, 0
    elif h < 120:
        r1, g1, b1 = x, c, 0
    elif h < 180:
        r1, g1, b1 = 0, c, x
    elif h < 240:
        r1, g1, b1 = 0, x, c
    elif h < 300:
        r1, g1, b1 = x, 0, c
    else:
        r1, g1, b1 = c, 0, x
    a = min(255, max(0, int(alpha * 255)))
    r = min(255, max(0, int((r1 + m) * 255)))
    g = min(255, max(0, int((g1 + m) * 255)))
    b = min(255, max(0, int((b1 + m) * 255)))
    return (a << 24) | (r << 16) | (g << 8) | b


def is_transparent(argb):
    return (argb >> 24) == 0


KNOWN = [
    # (argb, hue, sat, value, name)
    (0xFFFF0000, 0.0, 1.0, 1.0, "red"),
    (0xFF00FF00, 120.0, 1.0, 1.0, "green"),
    (0xFF0000FF, 240.0, 1.0, 1.0, "blue"),
    (0xFFFFFF00, 60.0, 1.0, 1.0, "yellow"),
    (0xFF00FFFF, 180.0, 1.0, 1.0, "cyan"),
    (0xFFFF00FF, 300.0, 1.0, 1.0, "magenta"),
    (0xFF000000, 0.0, 0.0, 0.0, "black"),
    (0xFFFFFFFF, 0.0, 0.0, 1.0, "white"),
    (0xFF808080, 0.0, 0.0, 128 / 255, "mid grey"),
]


def main():
    failures = 0

    print("=== known colours decompose correctly ===")
    print(f"{'name':10}{'hue':>7}{'sat':>7}{'val':>7}   ok")
    print("-" * 40)
    for argb, h, s, v, name in KNOWN:
        gh, gs, gv = rgb_to_hsv(argb)
        ok = abs(gh - h) < 0.5 and abs(gs - s) < 0.01 and abs(gv - v) < 0.01
        if not ok:
            failures += 1
        print(f"{name:10}{gh:>7.1f}{gs:>7.2f}{gv:>7.2f}   {'ok' if ok else 'FAIL'}")

    print("\n=== round trip: rgb -> hsv -> rgb ===")
    print(f"{'input':>12}{'output':>12}   ok")
    print("-" * 34)
    # The app's own presets plus a few arbitrary values.
    samples = [
        0xFFE53935, 0xFFFFB300, 0xFF43A047, 0xFF1E88E5, 0xFF000000, 0xFFFFFFFF,
        0xFF123456, 0xFF7F3FBF, 0xFF00806F,
    ]
    for argb in samples:
        h, s, v = rgb_to_hsv(argb)
        back = hsv_to_rgb(h, s, v, 1.0)
        # Allow 1/255 per channel for integer rounding.
        ok = all(
            abs(((argb >> sh) & 0xFF) - ((back >> sh) & 0xFF)) <= 1
            for sh in (16, 8, 0)
        )
        if not ok:
            failures += 1
        print(f"  #{argb:08X}  #{back:08X}   {'ok' if ok else 'FAIL'}")

    print("\n=== alpha is preserved ===")
    for a in (0.0, 0.25, 0.5, 1.0):
        c = hsv_to_rgb(0, 1, 1, a)
        got = (c >> 24) / 255.0
        ok = abs(got - a) < 0.01
        if not ok:
            failures += 1
        print(f"  alpha {a:.2f} -> {got:.2f}   {'ok' if ok else 'FAIL'}")

    print("\n=== transparency detection ===")
    cases = [
        (0x00000000, True, "fully transparent"),
        (0x00FF0000, True, "alpha 0 but red bits set"),
        (0xFF000000, False, "opaque black"),
        (0x01FF0000, False, "alpha 1 (nearly invisible, still a colour)"),
    ]
    for argb, want, label in cases:
        got = is_transparent(argb)
        ok = got == want
        if not ok:
            failures += 1
        print(f"  {label:42} {str(got):>5}  {'ok' if ok else 'FAIL'}")

    # The reason transparency is alpha-0 rather than null: the hue must survive, so
    # toggling fill off and back on does not lose the user's chosen colour.
    print("\n=== a transparent value still carries its hue ===")
    chosen = hsv_to_rgb(210, 0.8, 0.9, 1.0)
    turned_off = chosen & 0x00FFFFFF          # alpha stripped
    h_before = rgb_to_hsv(chosen)[0]
    h_after = rgb_to_hsv(turned_off)[0]
    ok = abs(h_before - h_after) < 0.5 and is_transparent(turned_off)
    if not ok:
        failures += 1
    print(f"  hue {h_before:.1f} -> {h_after:.1f} while transparent   "
          f"{'ok' if ok else 'FAIL'}")

    print()
    if failures:
        print(f"FAIL: {failures} check(s) failed")
        raise SystemExit(1)
    print("Colour conversion, alpha and transparency all behave correctly.")


if __name__ == "__main__":
    main()
