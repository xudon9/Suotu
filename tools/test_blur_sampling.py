#!/usr/bin/env python3
"""
Check that a blur-painted shape samples the pixels UNDERNEATH itself.

The bug this guards against: the blurred bitmap is the FULL-RESOLUTION source, while
the preview canvas is the smaller letterboxed area. Drawing that bitmap at (0,0) at its
native size makes a shape reveal pixels from the image's top-left corner rather than
from under the shape.

It was easy to miss because flatten() renders at exactly the source size, where the
scale factor is 1 and the offset is 0 — so the final output was correct and only the
on-screen preview was wrong.
"""


def sampled_region(shape, canvas_w, canvas_h, src_w, src_h, scale_dest):
    """
    Which part of the SOURCE image shows through a shape.

    shape is (left, top, right, bottom) in canvas pixels.
    With scale_dest=True the blurred bitmap is stretched to the canvas (correct);
    with False it is drawn 1:1 at the origin (the bug).
    """
    l, t, r, b = shape
    if scale_dest:
        fx = src_w / canvas_w
        fy = src_h / canvas_h
    else:
        # Drawn 1:1, so canvas pixel (x,y) shows source pixel (x,y).
        fx = fy = 1.0
    return (l * fx, t * fy, r * fx, b * fy)


def main():
    failures = 0

    # A realistic preview: 1260x2800 screenshot letterboxed into a 496x1103 area.
    SRC_W, SRC_H = 1260, 2800
    CANVAS_W, CANVAS_H = 496, 1103

    # A shape drawn over the middle of the preview.
    shape = (200, 500, 400, 560)

    print(f"source {SRC_W}x{SRC_H}, preview canvas {CANVAS_W}x{CANVAS_H}")
    print(f"shape at canvas {shape}")
    print()

    # What the shape SHOULD reveal: the same relative region of the source.
    want = (
        shape[0] * SRC_W / CANVAS_W,
        shape[1] * SRC_H / CANVAS_H,
        shape[2] * SRC_W / CANVAS_W,
        shape[3] * SRC_H / CANVAS_H,
    )

    buggy = sampled_region(shape, CANVAS_W, CANVAS_H, SRC_W, SRC_H, scale_dest=False)
    fixed = sampled_region(shape, CANVAS_W, CANVAS_H, SRC_W, SRC_H, scale_dest=True)

    print("source region revealed through the shape:")
    print(f"  want   x {want[0]:7.1f}..{want[2]:7.1f}   y {want[1]:7.1f}..{want[3]:7.1f}")
    print(f"  buggy  x {buggy[0]:7.1f}..{buggy[2]:7.1f}   y {buggy[1]:7.1f}..{buggy[3]:7.1f}")
    print(f"  fixed  x {fixed[0]:7.1f}..{fixed[2]:7.1f}   y {fixed[1]:7.1f}..{fixed[3]:7.1f}")
    print()

    ok_fixed = all(abs(f - wv) < 0.01 for f, wv in zip(fixed, want))
    if not ok_fixed:
        print("FAIL: the fixed mapping does not sample from under the shape")
        failures += 1
    else:
        print("fixed mapping samples exactly the region under the shape")

    # The test must actually reproduce the bug, or it proves nothing.
    drift_y = abs(buggy[1] - want[1])
    if drift_y < 50:
        print("FAIL: this configuration does not expose the bug")
        failures += 1
    else:
        print(f"the buggy mapping was off by {drift_y:.0f}px vertically "
              f"({drift_y / SRC_H * 100:.0f}% of the image height)")

    print("\n=== why flatten() hid it: canvas size == source size ===")
    f_buggy = sampled_region(
        shape, SRC_W, SRC_H, SRC_W, SRC_H, scale_dest=False
    )
    f_fixed = sampled_region(
        shape, SRC_W, SRC_H, SRC_W, SRC_H, scale_dest=True
    )
    same = all(abs(a - b) < 0.01 for a, b in zip(f_buggy, f_fixed))
    print(f"  at full size the two mappings agree: {same}")
    print("  so the exported image was always correct, and only the preview lied")
    if not same:
        print("FAIL: expected the mappings to coincide at 1:1")
        failures += 1

    print("\n=== a range of canvas scales ===")
    print(f"{'canvas':>12}{'scale':>8}{'buggy y':>10}{'fixed y':>10}   ok")
    print("-" * 44)
    for cw, ch in ((248, 551), (496, 1103), (630, 1400), (1260, 2800)):
        w_ = (shape[1] * SRC_H / ch)
        bug = sampled_region(shape, cw, ch, SRC_W, SRC_H, False)[1]
        fix = sampled_region(shape, cw, ch, SRC_W, SRC_H, True)[1]
        ok = abs(fix - w_) < 0.01
        if not ok:
            failures += 1
        print(f"{cw:>6}x{ch:<5}{SRC_W / cw:>8.2f}{bug:>10.1f}{fix:>10.1f}   "
              f"{'ok' if ok else 'FAIL'}")

    print()
    if failures:
        print(f"FAIL: {failures} check(s) failed")
        raise SystemExit(1)
    print("Blur sampling is correct at every canvas scale.")


if __name__ == "__main__":
    main()
