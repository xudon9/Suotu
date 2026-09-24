#!/usr/bin/env python3
"""
Check the notification thumbnail sizing.

Why this is worth a test: a notification crosses a Binder transaction with a hard size
limit, and an oversized bitmap does not raise an error the user ever sees — the
notification is simply dropped. That failure looks identical to "the feature does not
work", which is the worst kind of bug to debug from a user report.

So this checks that the inSampleSize maths actually lands under the cap for the image
shapes the app really sees, and that decoding never allocates the full-size bitmap.
"""

# Mirrors ShrinkNotifier.
BIG_PICTURE_PX = 384
LARGE_ICON_PX = 128

# Binder's documented transaction buffer is 1 MB, shared across the whole transaction.
# Staying an order of magnitude under it is the point of sampling down.
BINDER_LIMIT_BYTES = 1024 * 1024
SAFE_BUDGET_BYTES = BINDER_LIMIT_BYTES // 2

# RGB_565 is what the decoder is asked for: 2 bytes per pixel instead of 4.
BYTES_PER_PIXEL = 2


def sample_size(longest, max_px):
    """Mirror of the inSampleSize loop in ShrinkNotifier.thumbnail."""
    sample = 1
    while longest // (sample * 2) >= max_px:
        sample *= 2
    return sample


def decoded(w, h, max_px):
    s = sample_size(max(w, h), max_px)
    return w // s, h // s, s


SHAPES = [
    ("vivo V2507A screenshot", 1260, 2800),
    ("Pixel 7 emulator", 1080, 2400),
    ("tablet landscape", 2560, 1600),
    ("square crop", 2000, 2000),
    ("panorama", 8000, 1200),
    ("very tall stitched shot", 1080, 12000),
    ("tiny thumbnail source", 200, 300),
    ("already small", 64, 64),
]


def main():
    failures = 0

    print("=== expanded thumbnail stays inside the transaction budget ===")
    print(f"{'source':26}{'sample':>7}{'decoded':>13}{'bytes':>10}   ok")
    print("-" * 60)
    for label, w, h in SHAPES:
        dw, dh, s = decoded(w, h, BIG_PICTURE_PX)
        size = dw * dh * BYTES_PER_PIXEL
        ok = size <= SAFE_BUDGET_BYTES
        if not ok:
            failures += 1
        print(f"{label:26}{s:>7}{f'{dw}x{dh}':>13}{size // 1024:>9}K   "
              f"{'ok' if ok else 'FAIL'}")

    print("\n=== collapsed thumbnail too ===")
    for label, w, h in SHAPES:
        dw, dh, s = decoded(w, h, LARGE_ICON_PX)
        size = dw * dh * BYTES_PER_PIXEL
        if size > SAFE_BUDGET_BYTES:
            print(f"  FAIL: {label} -> {size // 1024}K")
            failures += 1
    print("  all within budget")

    print("\n=== sampling actually avoids the full-size allocation ===")
    print(f"{'source':26}{'full':>10}{'sampled':>10}{'saved':>9}")
    print("-" * 55)
    for label, w, h in SHAPES:
        full = w * h * 4  # ARGB_8888, what a naive decode would cost
        dw, dh, _ = decoded(w, h, BIG_PICTURE_PX)
        small = dw * dh * BYTES_PER_PIXEL
        ratio = full / small if small else 0
        print(f"{label:26}{full // 1024:>9}K{small // 1024:>9}K{ratio:>8.0f}x")
        # A big screenshot must be sampled down substantially, or the loop is wrong.
        if max(w, h) >= 2 * BIG_PICTURE_PX and ratio < 4:
            print(f"  FAIL: {label} was barely reduced")
            failures += 1

    print("\n=== small sources are never upscaled or over-sampled ===")
    for label, w, h in (("tiny thumbnail source", 200, 300), ("already small", 64, 64)):
        dw, dh, s = decoded(w, h, BIG_PICTURE_PX)
        ok = s == 1 and (dw, dh) == (w, h)
        if not ok:
            failures += 1
        print(f"  {label:24} sample={s} -> {dw}x{dh}   {'ok' if ok else 'FAIL'}")

    print("\n=== the decoded image keeps roughly the requested longest side ===")
    # inSampleSize is powers of two, so the result lands between max_px and 2*max_px.
    for label, w, h in SHAPES:
        if max(w, h) <= BIG_PICTURE_PX:
            continue
        dw, dh, _ = decoded(w, h, BIG_PICTURE_PX)
        longest = max(dw, dh)
        ok = BIG_PICTURE_PX <= longest < BIG_PICTURE_PX * 2
        if not ok:
            failures += 1
            print(f"  FAIL: {label} longest={longest}")
    print(f"  every sampled result lands in [{BIG_PICTURE_PX}, {BIG_PICTURE_PX * 2})")

    print()
    if failures:
        print(f"FAIL: {failures} check(s) failed")
        raise SystemExit(1)
    print("Notification thumbnails stay small enough to actually be delivered.")


if __name__ == "__main__":
    main()
