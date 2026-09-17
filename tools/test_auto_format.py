#!/usr/bin/env python3
"""
Check Auto's format choice: the smaller file must win.

This exists because the rule and its label had drifted apart. Auto was presented as
"Auto (smallest)" but, when both candidates fitted the byte budget, the code compared
their QUALITY NUMBERS and kept the higher one. Two problems with that:

 - It could ship the larger file while the UI claimed to pick the smallest.
 - Comparing quality numbers across codecs is meaningless anyway. WebP q80 and JPEG q80
   are different scales; the number is not a common unit.

In practice both usually landed on q80 and WebP won on evaluation order, so the output
happened to be correct — which is exactly the kind of bug that survives casual testing.
"""


def pick_old(a, b, budget):
    """The previous rule: quality wins among candidates that fit."""
    a_fits = a["size"] <= budget
    b_fits = b["size"] <= budget
    if a_fits and not b_fits:
        return a
    if b_fits and not a_fits:
        return b
    if a_fits and b_fits:
        return a if a["quality"] >= b["quality"] else b
    return a if a["size"] <= b["size"] else b


def pick_new(a, b, budget):
    """The current rule: smaller file wins."""
    a_fits = a["size"] <= budget
    b_fits = b["size"] <= budget
    if a_fits and not b_fits:
        return a
    if b_fits and not a_fits:
        return b
    return a if a["size"] <= b["size"] else b


def fmt(c):
    return f"{c['name']} {c['size'] // 1024}KB q{c['quality']}"


CASES = [
    # (label, webp, jpeg, budget, expected format under the new rule)
    (
        "typical screenshot: both fit, WebP smaller",
        {"name": "WebP", "size": 14 * 1024, "quality": 80},
        {"name": "JPEG", "size": 31 * 1024, "quality": 80},
        120 * 1024,
        "WebP",
    ),
    (
        "the case the old rule got WRONG: JPEG bigger but higher q",
        {"name": "WebP", "size": 14 * 1024, "quality": 79},
        {"name": "JPEG", "size": 31 * 1024, "quality": 80},
        120 * 1024,
        "WebP",
    ),
    (
        "JPEG genuinely smaller (can happen on flat graphics)",
        {"name": "WebP", "size": 40 * 1024, "quality": 80},
        {"name": "JPEG", "size": 22 * 1024, "quality": 80},
        120 * 1024,
        "JPEG",
    ),
    (
        "only JPEG fits the budget",
        {"name": "WebP", "size": 200 * 1024, "quality": 80},
        {"name": "JPEG", "size": 90 * 1024, "quality": 70},
        120 * 1024,
        "JPEG",
    ),
    (
        "only WebP fits the budget",
        {"name": "WebP", "size": 90 * 1024, "quality": 70},
        {"name": "JPEG", "size": 200 * 1024, "quality": 80},
        120 * 1024,
        "WebP",
    ),
    (
        "neither fits: smaller still wins",
        {"name": "WebP", "size": 300 * 1024, "quality": 40},
        {"name": "JPEG", "size": 280 * 1024, "quality": 35},
        120 * 1024,
        "JPEG",
    ),
    (
        "exact tie on size: WebP wins on evaluation order",
        {"name": "WebP", "size": 20 * 1024, "quality": 80},
        {"name": "JPEG", "size": 20 * 1024, "quality": 80},
        120 * 1024,
        "WebP",
    ),
]


def main():
    failures = 0
    diverged = 0

    print(f"{'case':52}{'new':>7}{'old':>7}{'want':>7}")
    print("-" * 73)
    for label, webp, jpeg, budget, want in CASES:
        new = pick_new(webp, jpeg, budget)["name"]
        old = pick_old(webp, jpeg, budget)["name"]
        ok = new == want
        if not ok:
            failures += 1
        if new != old:
            diverged += 1
        mark = "" if ok else "  FAIL"
        print(f"{label:52}{new:>7}{old:>7}{want:>7}{mark}")

    print("-" * 73)

    # The label promises the smallest file; verify that literally.
    print("\nevery case picks the smaller of the two candidates that fit:")
    for label, webp, jpeg, budget, _ in CASES:
        chosen = pick_new(webp, jpeg, budget)
        fitting = [c for c in (webp, jpeg) if c["size"] <= budget]
        pool = fitting if fitting else [webp, jpeg]
        smallest = min(pool, key=lambda c: c["size"])
        if chosen["size"] != smallest["size"]:
            print(f"  FAIL: {label} chose {fmt(chosen)}, "
                  f"smallest available was {fmt(smallest)}")
            failures += 1
    print("  confirmed")

    if diverged == 0:
        print("\nFAIL: the new rule never differs from the old one — "
              "this test proves nothing")
        failures += 1
    else:
        print(f"\nthe two rules disagree on {diverged} of {len(CASES)} cases, "
              "so the change is observable")

    print()
    if failures:
        print(f"FAIL: {failures} check(s) failed")
        raise SystemExit(1)
    print("Auto picks the smallest file, as its label claims.")


if __name__ == "__main__":
    main()
