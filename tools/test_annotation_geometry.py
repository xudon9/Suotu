#!/usr/bin/env python3
"""
Check annotation geometry: arrowhead construction and hit-testing.

Two things here are easy to get subtly wrong and hard to spot by eye:

 1. The arrowhead must sit AT the drag end point and point along the line, at every
    angle. A head built by rotating the canvas, or with the angle sign flipped, still
    looks plausible in one direction and is wrong in the other three quadrants.

 2. Hit-testing must let a tap reach the shape the user meant. A diagonal arrow's
    bounding box is mostly empty, so testing the box would steal taps meant for
    whatever lies beneath.
"""

import math

ARROWHEAD_SCALE = 4.5
ARROWHEAD_ANGLE = 0.45


def arrowhead(x1, y1, x2, y2, stroke):
    """Mirror of AnnotationRenderer.drawArrow's head construction."""
    length = math.hypot(x2 - x1, y2 - y1)
    if length < 1:
        return None
    head_len = min(stroke * ARROWHEAD_SCALE, length * 0.5)
    angle = math.atan2(y2 - y1, x2 - x1)
    tip = (x2, y2)
    left = (x2 - math.cos(angle - ARROWHEAD_ANGLE) * head_len,
            y2 - math.sin(angle - ARROWHEAD_ANGLE) * head_len)
    right = (x2 - math.cos(angle + ARROWHEAD_ANGLE) * head_len,
             y2 - math.sin(angle + ARROWHEAD_ANGLE) * head_len)
    return tip, left, right, head_len, angle


def distance_to_segment(px, py, ax, ay, bx, by):
    """Mirror of AnnotationState.distanceToSegment."""
    dx, dy = bx - ax, by - ay
    len_sq = dx * dx + dy * dy
    if len_sq < 1e-9:
        return math.hypot(px - ax, py - ay)
    t = max(0.0, min(1.0, ((px - ax) * dx + (py - ay) * dy) / len_sq))
    return math.hypot(px - (ax + t * dx), py - (ay + t * dy))


def main():
    failures = 0

    print("=== arrowhead points along the line, in every direction ===")
    print(f"{'direction':14}{'tip on end':>12}{'symmetric':>11}{'points back':>13}")
    print("-" * 52)

    directions = [
        ("right", 100, 100, 300, 100),
        ("left", 300, 100, 100, 100),
        ("down", 100, 100, 100, 300),
        ("up", 100, 300, 100, 100),
        ("diag down-right", 100, 100, 300, 300),
        ("diag up-left", 300, 300, 100, 100),
        ("diag up-right", 100, 300, 300, 100),
        ("diag down-left", 300, 100, 100, 300),
    ]

    for label, x1, y1, x2, y2 in directions:
        tip, left, right, head_len, angle = arrowhead(x1, y1, x2, y2, 8.0)

        # The tip must be exactly the drag end point.
        tip_ok = abs(tip[0] - x2) < 1e-6 and abs(tip[1] - y2) < 1e-6

        # The two barbs must be equidistant from the tip.
        dl = math.hypot(left[0] - x2, left[1] - y2)
        dr = math.hypot(right[0] - x2, right[1] - y2)
        sym_ok = abs(dl - dr) < 1e-6

        # Both barbs must lie BEHIND the tip, i.e. back toward the start.
        ux, uy = math.cos(angle), math.sin(angle)
        proj_l = (left[0] - x2) * ux + (left[1] - y2) * uy
        proj_r = (right[0] - x2) * ux + (right[1] - y2) * uy
        back_ok = proj_l < 0 and proj_r < 0

        if not (tip_ok and sym_ok and back_ok):
            failures += 1
        print(f"{label:14}{'ok' if tip_ok else 'FAIL':>12}"
              f"{'ok' if sym_ok else 'FAIL':>11}"
              f"{'ok' if back_ok else 'FAIL':>13}")

    print("\n=== head is capped on very short arrows ===")
    # A short drag must not produce a head longer than the arrow itself.
    _, _, _, head_len, _ = arrowhead(100, 100, 110, 100, 20.0)
    length = 10.0
    print(f"arrow length {length}, stroke 20 -> head {head_len:.1f}")
    if head_len > length * 0.5 + 1e-6:
        print("FAIL: head longer than half the arrow")
        failures += 1
    else:
        print("ok (capped at half the arrow length)")

    print("\n=== hit-testing a diagonal arrow ===")
    # Arrow from (0.1,0.1) to (0.9,0.9) in normalised space.
    ax, ay, bx, by = 0.1, 0.1, 0.9, 0.9
    slop = 0.03
    probes = [
        ("on the line, midpoint", 0.5, 0.5, True),
        ("on the line, near start", 0.2, 0.2, True),
        ("just off the line", 0.5, 0.52, True),
        ("far off, but INSIDE bbox", 0.85, 0.15, False),
        ("far off, other corner", 0.15, 0.85, False),
        ("outside bbox entirely", 0.95, 0.05, False),
    ]
    print(f"{'probe':28}{'hit':>6}{'want':>7}")
    print("-" * 41)
    for label, px, py, want in probes:
        hit = distance_to_segment(px, py, ax, ay, bx, by) < slop
        ok = hit == want
        if not ok:
            failures += 1
        print(f"{label:28}{str(hit):>6}{str(want):>7}{'' if ok else '  FAIL'}")

    # Demonstrate why the bounding box is not good enough.
    in_bbox = (min(ax, bx) <= 0.85 <= max(ax, bx) and
               min(ay, by) <= 0.15 <= max(ay, by))
    print(f"\nthe point (0.85, 0.15) is inside the arrow's bounding box: {in_bbox}")
    print("testing the bbox would wrongly claim that tap for the arrow")
    if not in_bbox:
        print("FAIL: test does not demonstrate the bbox problem")
        failures += 1

    print()
    if failures:
        print(f"FAIL: {failures} check(s) failed")
        raise SystemExit(1)
    print("Arrowhead construction and hit-testing are correct.")


if __name__ == "__main__":
    main()
