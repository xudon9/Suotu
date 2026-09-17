#!/usr/bin/env python3
"""
Model the per-frame cost of dragging a shape while blur shapes already exist.

The reported symptom was specific and worth taking literally: the FIRST blur rectangle
dragged smoothly, the second and third lagged. That shape of complaint points at a cost
that grows with the number of committed shapes, not at a fixed one.

Costs are in megapixels touched per frame, which is what dominates here — every blur
shape clipped and blitted a full-canvas bitmap on every frame.
"""

# Preview canvas on the vivo: 1260x2800 letterboxed into the editor area.
CANVAS_W, CANVAS_H = 496, 1103
SOURCE_W, SOURCE_H = 1260, 2800

CANVAS_MP = CANVAS_W * CANVAS_H / 1e6
SOURCE_MP = SOURCE_W * SOURCE_H / 1e6


def before(n_committed_blur):
    """
    Old path: render() walks every committed item each frame, and each blur shape
    clips and blits the FULL-RESOLUTION blurred bitmap scaled into the canvas.
    """
    # A scaled blit reads the source and writes the canvas.
    per_shape = SOURCE_MP + CANVAS_MP
    return n_committed_blur * per_shape


def after(n_committed_blur):
    """
    New path: committed items are rendered once into an overlay bitmap, which is
    blitted 1:1. Cost is independent of the shape count.
    """
    return CANVAS_MP


def main():
    print(f"preview canvas {CANVAS_W}x{CANVAS_H} = {CANVAS_MP:.2f} MP")
    print(f"source bitmap  {SOURCE_W}x{SOURCE_H} = {SOURCE_MP:.2f} MP")
    print()
    print("megapixels touched per frame while dragging a new shape:")
    print(f"{'committed blur shapes':>24}{'before':>10}{'after':>9}{'speedup':>10}")
    print("-" * 53)

    failures = 0
    for n in (1, 2, 3, 5):
        b = before(n)
        a = after(n)
        print(f"{n:>24}{b:>9.2f}M{a:>8.2f}M{b / a:>9.1f}x")
        if a > b:
            print("FAIL: the new path is slower")
            failures += 1

    print()
    # The complaint was that cost GREW with shape count. Check that it no longer does.
    growth_before = before(3) / before(1)
    growth_after = after(3) / after(1)
    print(f"cost growth from 1 to 3 shapes:  before {growth_before:.1f}x, "
          f"after {growth_after:.1f}x")
    if growth_after > 1.01:
        print("FAIL: per-frame cost still grows with the number of shapes")
        failures += 1
    else:
        print("per-frame cost is now flat in the number of committed shapes")

    print()
    print("=== blur recomputation cost (cache at canvas size vs source size) ===")
    # A Gaussian pass is linear in pixels x kernel width; only the pixel count differs.
    print(f"  blurring at source size: {SOURCE_MP:.2f} MP")
    print(f"  blurring at canvas size: {CANVAS_MP:.2f} MP")
    ratio = SOURCE_MP / CANVAS_MP
    print(f"  -> {ratio:.1f}x less work when the blur is cached pre-scaled")
    if ratio < 2:
        print("FAIL: pre-scaling is not worth the added complexity here")
        failures += 1

    print()
    if failures:
        print(f"FAIL: {failures} check(s) failed")
        raise SystemExit(1)
    print("Per-frame drag cost is constant and substantially lower.")


if __name__ == "__main__":
    main()
