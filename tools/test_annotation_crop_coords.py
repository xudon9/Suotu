#!/usr/bin/env python3
"""
Check that annotations drawn on the CROPPED view land correctly in the output.

The bug this pins: the annotation editor was handed the cropped bitmap so the view
matched the work, but no coordinate conversion was added. The engine annotates the
WHOLE image and crops afterwards — annotations are whole-image coordinates by design —
so cropped-space coordinates stored as-is were interpreted at whole-image scale. Every
shape came out enlarged and displaced: a circle drawn at the centre of a top-left
quarter crop landed at the centre of the full image, then got cropped away from where
it was drawn.

The fix converts at the editor's boundary:
    full = crop.origin + local * crop.size
and this test models the whole pipeline — draw, convert, engine draw, crop — to assert
that what the user drew is what the output shows.

A full-frame crop is the degenerate case where both spaces coincide, which is exactly
why the bug survived a "looks fine" test: without a crop, nothing is displaced.
"""


def to_full(local, crop):
    """Editor boundary: cropped-space point -> whole-image point."""
    l, t, r, b = crop
    w, h = r - l, b - t
    return (l + local[0] * w, t + local[1] * h)


def to_local(full, crop):
    """The inverse, used by the test to read positions back out of the crop."""
    l, t, r, b = crop
    w, h = r - l, b - t
    return ((full[0] - l) / w, (full[1] - t) / h)


def engine_pipeline(stored_point, crop):
    """
    Where a STORED (whole-image) point lands inside the cropped output.

    The engine draws at the stored point on the whole image, then crops; reading the
    point back out of the cropped region gives its position in the output.
    """
    return to_local(stored_point, crop)


def buggy_pipeline(local_point, crop):
    """What the shipped bug did: stored local coords, engine read them as whole-image."""
    return to_local(local_point, crop)


CROPS = [
    ((0.0, 0.0, 1.0, 1.0), "full frame"),
    ((0.0, 0.0, 0.5, 0.5), "top-left quarter"),
    ((0.25, 0.25, 0.75, 0.75), "centre half"),
    ((0.5, 0.0, 1.0, 0.5), "top-right half"),
    ((0.0, 0.5, 0.4, 1.0), "bottom-left tall"),
    ((0.1, 0.1, 0.9, 0.35), "wide strip"),
]

DRAWN = [
    ((0.5, 0.5), "centre of the view"),
    ((0.2, 0.8), "lower left"),
    ((0.75, 0.3), "upper right"),
    ((0.5, 0.1), "top centre"),
]


def main():
    failures = 0

    print("=== drawn position survives the pipeline (the fix) ===")
    print(f"{'crop':24}{'drawn':>16}{'lands at':>16}   ok")
    print("-" * 62)
    for crop, label in CROPS:
        for pt, _ in DRAWN:
            stored = to_full(pt, crop)
            landed = engine_pipeline(stored, crop)
            ok = all(abs(a - b) < 1e-9 for a, b in zip(landed, pt))
            if not ok:
                failures += 1
            print(f"{label:24}{str(pt):>16}{str(landed):>16}   "
                  f"{'ok' if ok else 'FAIL'}")

    print("\n=== the stored point lies inside the crop it was drawn against ===")
    for crop, label in CROPS:
        l, t, r, b = crop
        for pt, _ in DRAWN:
            full = to_full(pt, crop)
            inside = l - 1e-9 <= full[0] <= r + 1e-9 and t - 1e-9 <= full[1] <= b + 1e-9
            if not inside:
                failures += 1
                print(f"  FAIL {label}: {pt} -> {full} escapes the crop")
    print("  every drawn point stays inside the crop")

    print("\n=== sizes survive too (a shape's extent is not enlarged) ===")
    # A radius-0.1 circle drawn on the cropped view must still span 0.1 of the OUTPUT,
    # not 0.1 of the whole image. Model the radius by mapping two points.
    for crop, label in CROPS:
        p1, p2 = (0.4, 0.5), (0.6, 0.5)  # spans 0.2 of the view
        span_out = abs(
            engine_pipeline(to_full(p1, crop), crop)[0]
            - engine_pipeline(to_full(p2, crop), crop)[0]
        )
        ok = abs(span_out - 0.2) < 1e-9
        if not ok:
            failures += 1
        print(f"  {label:24} drawn span 0.20 -> output span {span_out:.4f}   "
              f"{'ok' if ok else 'FAIL'}")

    print("\n=== the bug this replaces: displacement without the conversion ===")
    print(f"{'crop':24}{'drawn':>16}{'bug lands at':>16}{'shift':>9}")
    print("-" * 68)
    any_displaced = False
    for crop, label in CROPS:
        for pt, _ in DRAWN:
            landed = buggy_pipeline(pt, crop)
            shift = max(abs(a - b) for a, b in zip(landed, pt))
            if shift > 1e-9:
                any_displaced = True
            print(f"{label:24}{str(pt):>16}{str(landed):>16}{shift:>9.3f}")
    if not any_displaced:
        print("  FAIL: no displacement modelled — this test proves nothing")
        failures += 1

    print("\n=== full-frame crop: the degenerate case both get right ===")
    full_frame = (0.0, 0.0, 1.0, 1.0)
    for pt, _ in DRAWN:
        fixed = engine_pipeline(to_full(pt, full_frame), full_frame)
        buggy = buggy_pipeline(pt, full_frame)
        ok = all(abs(a - b) < 1e-9 for a, b in zip(fixed, buggy))
        if not ok:
            failures += 1
    print("  identical — which is why a no-crop test could not catch the bug")

    print("\n=== round trip: local -> full -> local ===")
    for crop, label in CROPS:
        for pt, _ in DRAWN:
            back = to_local(to_full(pt, crop), crop)
            ok = all(abs(a - b) < 1e-9 for a, b in zip(back, pt))
            if not ok:
                failures += 1
                print(f"  FAIL {label}: {pt} -> {back}")
    print("  exact for every crop shape")

    print()
    if failures:
        print(f"FAIL: {failures} check(s) failed")
        raise SystemExit(1)
    print("Annotations drawn on the cropped view land exactly where they were drawn.")


if __name__ == "__main__":
    main()
