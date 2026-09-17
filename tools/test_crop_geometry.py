#!/usr/bin/env python3
"""
Check the crop hit-testing geometry that made the tool "only crop vertically".

The original code computed the grab zone as `44f / widthInPixels`, treating a dp
number as if the divisor were dp too. On a 1080-px-wide canvas that yields 0.041,
which then clamped at the 0.04 floor — so BOTH axes used ~0.04 normalised units. On a
tall screenshot 0.04 of the height is ~2.2x more real distance than 0.04 of the width,
so vertical handles swallowed nearly every touch.

This mirrors the fixed logic and asserts the grab zone is the same PHYSICAL size on
both axes, and that touches near each handle resolve to that handle.
"""

# Canvas as laid out on the phone: full width, aspect from a 1260x2800 screenshot.
CANVAS_W = 1080.0
SRC_W, SRC_H = 1260.0, 2800.0
CANVAS_H = CANVAS_W * SRC_H / SRC_W

DENSITY = 2.8125          # vivo V2507A: 1260px / 448dp
GRAB_DP = 28.0
GRAB_PX = GRAB_DP * DENSITY


def slops(grab_px, w, h):
    sx = min(max(grab_px / w, 0.02), 0.25)
    sy = min(max(grab_px / h, 0.02), 0.25)
    return sx, sy


def old_slops(w, h):
    """The buggy version: dp number over a pixel dimension."""
    sx = min(max(44.0 / w, 0.04), 0.2)
    sy = min(max(44.0 / (w / (SRC_W / SRC_H)), 0.04), 0.2)
    return sx, sy


def pick(crop, nx, ny, sx, sy):
    l, t, r, b = crop
    dl, dr = abs(nx - l), abs(nx - r)
    dt, db = abs(ny - t), abs(ny - b)
    near_l, near_r = dl < sx, dr < sx
    near_t, near_b = dt < sy, db < sy

    corners = []
    if near_l and near_t: corners.append(("TL", dl / sx + dt / sy))
    if near_r and near_t: corners.append(("TR", dr / sx + dt / sy))
    if near_l and near_b: corners.append(("BL", dl / sx + db / sy))
    if near_r and near_b: corners.append(("BR", dr / sx + db / sy))
    if corners:
        return min(corners, key=lambda x: x[1])[0]

    within_v = t - sy < ny < b + sy
    within_h = l - sx < nx < r + sx
    edges = []
    if near_l and within_v: edges.append(("LEFT", dl / sx))
    if near_r and within_v: edges.append(("RIGHT", dr / sx))
    if near_t and within_h: edges.append(("TOP", dt / sy))
    if near_b and within_h: edges.append(("BOTTOM", db / sy))
    if edges:
        return min(edges, key=lambda x: x[1])[0]

    if l < nx < r and t < ny < b:
        return "INSIDE"
    return "NONE"


def main():
    print(f"canvas {CANVAS_W:.0f} x {CANVAS_H:.0f} px   density {DENSITY}")
    print(f"grab radius {GRAB_DP:.0f}dp = {GRAB_PX:.0f}px\n")

    osx, osy = old_slops(CANVAS_W, CANVAS_H)
    nsx, nsy = slops(GRAB_PX, CANVAS_W, CANVAS_H)

    print("grab zone as ACTUAL pixels on screen:")
    print(f"{'':10}{'horizontal':>12}{'vertical':>12}{'ratio':>8}")
    for name, (sx, sy) in (("old", (osx, osy)), ("fixed", (nsx, nsy))):
        px_x = sx * CANVAS_W
        px_y = sy * CANVAS_H
        print(f"{name:10}{px_x:>11.0f}px{px_y:>11.0f}px{px_y / px_x:>8.2f}")

    print("\nA ratio far from 1.00 is the bug: the vertical handle covers that many")
    print("times more screen distance than the horizontal one.\n")

    crop = (0.1, 0.1, 0.9, 0.9)
    # Touch 10px outside each handle, in normalised units.
    off_x = 10.0 / CANVAS_W
    off_y = 10.0 / CANVAS_H
    cases = [
        ("left edge midpoint",   0.1 + off_x, 0.5,         "LEFT"),
        ("right edge midpoint",  0.9 - off_x, 0.5,         "RIGHT"),
        ("top edge midpoint",    0.5,         0.1 + off_y, "TOP"),
        ("bottom edge midpoint", 0.5,         0.9 - off_y, "BOTTOM"),
        ("top-left corner",      0.1 + off_x, 0.1 + off_y, "TL"),
        ("bottom-right corner",  0.9 - off_x, 0.9 - off_y, "BR"),
        ("centre",               0.5,         0.5,         "INSIDE"),
    ]

    print(f"{'touch near':24}{'old':>10}{'fixed':>10}{'want':>10}")
    print("-" * 54)
    failures = 0
    for label, nx, ny, want in cases:
        got_old = pick(crop, nx, ny, osx, osy)
        got_new = pick(crop, nx, ny, nsx, nsy)
        ok = got_new == want
        if not ok:
            failures += 1
        mark = "" if ok else "  <-- FAIL"
        print(f"{label:24}{got_old:>10}{got_new:>10}{want:>10}{mark}")

    print("-" * 54)
    ratio = (nsy * CANVAS_H) / (nsx * CANVAS_W)
    if abs(ratio - 1.0) > 0.01:
        print(f"FAIL: grab zone still asymmetric (ratio {ratio:.2f})")
        raise SystemExit(1)
    if failures:
        print(f"FAIL: {failures} handle(s) resolve incorrectly")
        raise SystemExit(1)

    print("Grab zone is symmetric and every handle resolves correctly.")

    # The cases above are dead-centre on each handle, which even the broken geometry
    # got right. Real fingers are sloppy, so sweep a realistic scatter of touches and
    # measure how many LAND on the handle the user was aiming at.
    print("\nRealistic-touch sweep: offsets a finger actually achieves")
    print(f"{'aim':22}{'offset':>9}{'old':>10}{'fixed':>10}")
    print("-" * 51)

    import itertools
    aims = [
        ("LEFT", 0.1, 0.5),
        ("RIGHT", 0.9, 0.5),
        ("TOP", 0.5, 0.1),
        ("BOTTOM", 0.5, 0.9),
        ("TL", 0.1, 0.1),
        ("BR", 0.9, 0.9),
    ]
    old_hits = new_hits = total = 0
    for want, ax, ay in aims:
        for off_px in (15, 30, 50):
            # Offset inward along both axes, as a thumb tends to.
            dx = off_px / CANVAS_W * (1 if ax < 0.5 else -1)
            dy = off_px / CANVAS_H * (1 if ay < 0.5 else -1)
            nx = ax + dx
            ny = ay + dy
            go = pick(crop, nx, ny, osx, osy)
            gn = pick(crop, nx, ny, nsx, nsy)
            total += 1
            if go == want:
                old_hits += 1
            if gn == want:
                new_hits += 1
            print(f"{want:22}{off_px:>8}px{go:>10}{gn:>10}")

    print("-" * 51)
    print(f"aimed-handle hit rate:  old {old_hits}/{total}   fixed {new_hits}/{total}")
    if new_hits <= old_hits:
        print("FAIL: the fix did not improve real-touch accuracy")
        raise SystemExit(1)


if __name__ == "__main__":
    main()
