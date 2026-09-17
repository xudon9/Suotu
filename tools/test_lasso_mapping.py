#!/usr/bin/env python3
"""
Verify the lasso's coordinate mapping and rectangle composition.

Two things are easy to get silently wrong here, and both would skew every selection
rather than failing loudly:

 1. The preview is letterboxed (ContentScale.Fit). Touch coordinates must be shifted by
    the letterbox offset and divided by the DISPLAYED extent, not the box extent. The
    earlier handle-based crop shipped a related unit bug, so this is checked directly.

 2. A second selection is drawn on an already-cropped preview, so it is relative to the
    current crop and must be composed with it. Treating it as absolute would make the
    view jump somewhere unrelated on the second drag.
"""


def fit(box_w, box_h, img_w, img_h):
    """Mirror of FittedImage.fit."""
    scale = min(box_w / img_w, box_h / img_h)
    dw, dh = img_w * scale, img_h * scale
    return ((box_w - dw) / 2.0, (box_h - dh) / 2.0, dw, dh)


def to_normalised(f, x, y):
    ox, oy, dw, dh = f
    nx = (x - ox) / dw
    ny = (y - oy) / dh
    if nx < -0.05 or nx > 1.05 or ny < -0.05 or ny > 1.05:
        return None
    return (min(max(nx, 0.0), 1.0), min(max(ny, 0.0), 1.0))


def to_box(f, nx, ny):
    ox, oy, dw, dh = f
    return (ox + nx * dw, oy + ny * dh)


def compose(base, sel):
    """Mirror of CropRect.compose."""
    bl, bt, br, bb = base
    w, h = br - bl, bb - bt
    return (bl + sel[0] * w, bt + sel[1] * h, bl + sel[2] * w, bt + sel[3] * h)


def approx(a, b, tol=1e-4):
    return abs(a - b) <= tol


def main():
    failures = 0

    # A tall screenshot inside a wide-ish preview box: heavy letterboxing.
    BOX_W, BOX_H = 1000.0, 800.0
    IMG_W, IMG_H = 1260, 2800
    f = fit(BOX_W, BOX_H, IMG_W, IMG_H)
    ox, oy, dw, dh = f

    print("=== letterbox geometry ===")
    print(f"box {BOX_W:.0f}x{BOX_H:.0f}, image {IMG_W}x{IMG_H}")
    print(f"displayed {dw:.1f}x{dh:.1f} at offset ({ox:.1f}, {oy:.1f})")
    print(f"pillarbox bars: {ox:.1f}px each side\n")

    if not approx(dh, BOX_H):
        print("FAIL: tall image should be height-limited")
        failures += 1
    if ox <= 0:
        print("FAIL: expected horizontal letterboxing")
        failures += 1

    print("=== touch -> normalised ===")
    cases = [
        ("image top-left", ox, oy, (0.0, 0.0)),
        ("image centre", ox + dw / 2, oy + dh / 2, (0.5, 0.5)),
        ("image bottom-right", ox + dw, oy + dh, (1.0, 1.0)),
        ("quarter across", ox + dw * 0.25, oy + dh * 0.75, (0.25, 0.75)),
    ]
    for label, x, y, want in cases:
        got = to_normalised(f, x, y)
        ok = got is not None and approx(got[0], want[0]) and approx(got[1], want[1])
        if not ok:
            failures += 1
        print(f"{label:22} -> {got}  want {want}  {'ok' if ok else 'FAIL'}")

    # The bug this guards against: ignoring the letterbox offset.
    #
    # Probe an OFF-CENTRE point deliberately: at the exact centre the image and the box
    # share a midpoint, so a broken mapping still returns 0.5 and the check proves
    # nothing. A first version of this test made exactly that mistake.
    probe_n = 0.25
    probe_x = ox + dw * probe_n
    correct = to_normalised(f, probe_x, oy + dh / 2)[0]
    naive = probe_x / BOX_W          # what you get if the offset is ignored
    print(f"\nprobing x at {probe_n} of the image width:")
    print(f"  correct mapping -> {correct:.3f}")
    print(f"  ignoring offset -> {naive:.3f}  (off by "
          f"{abs(naive - correct) * 100:.0f}% of the image)")
    if approx(naive, correct):
        print("FAIL: this configuration does not exercise the offset")
        failures += 1

    print("\n=== a touch in the letterbox bar is rejected ===")
    outside = to_normalised(f, ox / 2, BOX_H / 2)
    print(f"touch at x={ox/2:.0f} (inside the bar) -> {outside}")
    if outside is not None:
        print("FAIL: should be rejected as outside the image")
        failures += 1

    print("\n=== round trip ===")
    for nx, ny in ((0.0, 0.0), (0.33, 0.66), (1.0, 1.0)):
        bx, by = to_box(f, nx, ny)
        back = to_normalised(f, bx, by)
        ok = approx(back[0], nx) and approx(back[1], ny)
        if not ok:
            failures += 1
        print(f"({nx}, {ny}) -> box({bx:.1f}, {by:.1f}) -> {back}  "
              f"{'ok' if ok else 'FAIL'}")

    print("\n=== composing successive selections ===")
    # Crop to the middle half, then select the middle half of THAT.
    base = (0.25, 0.25, 0.75, 0.75)
    sel = (0.25, 0.25, 0.75, 0.75)
    got = compose(base, sel)
    want = (0.375, 0.375, 0.625, 0.625)
    ok = all(approx(g, w) for g, w in zip(got, want))
    if not ok:
        failures += 1
    print(f"base {base}")
    print(f"then {sel}")
    print(f"  -> {tuple(round(v, 4) for v in got)}  want {want}  "
          f"{'ok' if ok else 'FAIL'}")

    naive_wrong = sel
    print(f"treating it as absolute would give {naive_wrong} — a visible jump")

    # Selecting the full frame of a cropped view must be a no-op.
    full = compose(base, (0.0, 0.0, 1.0, 1.0))
    ok = all(approx(g, w) for g, w in zip(full, base))
    if not ok:
        failures += 1
    print(f"\nselecting everything within a crop -> {tuple(round(v,4) for v in full)}  "
          f"{'ok (no-op)' if ok else 'FAIL'}")

    print()
    if failures:
        print(f"FAIL: {failures} check(s) failed")
        raise SystemExit(1)
    print("Coordinate mapping and composition are correct.")


if __name__ == "__main__":
    main()
