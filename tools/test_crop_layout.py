#!/usr/bin/env python3
"""
Check that the crop screen's action buttons stay on screen.

The bug: CropEditor laid the canvas out as fillMaxWidth().aspectRatio(ratio). For a
tall screenshot that makes the canvas taller than the display, so the Apply/Cancel
row below it fell off the bottom. It was unreachable because the parent's
verticalScroll had been disabled (to stop it stealing crop drags).

The fix: the canvas takes weight(1f) of a bounded full-height Column and letterboxes
inside it, so the action row is measured first and always has space.
"""

# Devices to check, including a small phone and a squat tablet.
DEVICES = [
    ("vivo V2507A", 1260, 2800, 2.8125),
    ("small phone", 720, 1280, 2.0),
    ("tablet landscape", 2560, 1600, 2.0),
]

# Screenshot shapes a user might crop.
IMAGES = [
    ("tall screenshot", 1260, 2800),
    ("square photo", 1000, 1000),
    ("wide panorama", 3000, 1000),
]

# Fixed chrome around the canvas, in dp.
PAD_DP = 20.0 * 2
TITLE_DP = 32.0
HINT_DP = 34.0
GAP_DP = 12.0 + 8.0
BUTTON_ROW_DP = 48.0
SYSTEM_BARS_DP = 60.0


def old_layout(screen_w, screen_h, density, img_w, img_h):
    """Canvas = full width, height from aspect ratio. Returns button-row top in px."""
    pad_px = PAD_DP * density
    canvas_w = screen_w - pad_px
    canvas_h = canvas_w * img_h / img_w          # unbounded: can exceed the screen
    chrome = (TITLE_DP + HINT_DP + GAP_DP + SYSTEM_BARS_DP) * density
    return chrome + canvas_h


def new_layout(screen_w, screen_h, density, img_w, img_h):
    """Canvas = leftover space, letterboxed. Returns button-row top in px."""
    pad_px = PAD_DP * density
    reserved = (
        TITLE_DP + HINT_DP + GAP_DP + BUTTON_ROW_DP + SYSTEM_BARS_DP
    ) * density
    avail_w = screen_w - pad_px
    avail_h = screen_h - reserved

    # aspectRatio inside a bounded box: shrink to fit whichever axis binds.
    scale = min(avail_w / img_w, avail_h / img_h)
    canvas_h = img_h * scale

    chrome = (TITLE_DP + HINT_DP + GAP_DP + SYSTEM_BARS_DP) * density
    return chrome + canvas_h


def main():
    print("Button-row top edge vs screen height (px). Must stay ABOVE the bottom.\n")
    print(f"{'device':20}{'image':18}{'screen_h':>10}{'old':>10}{'fixed':>10}{'':>4}")
    print("-" * 74)

    failures = 0
    old_failures = 0
    for dname, sw, sh, dens in DEVICES:
        for iname, iw, ih in IMAGES:
            old_top = old_layout(sw, sh, dens, iw, ih)
            new_top = new_layout(sw, sh, dens, iw, ih)
            room = BUTTON_ROW_DP * dens
            old_ok = old_top + room <= sh
            new_ok = new_top + room <= sh
            if not old_ok:
                old_failures += 1
            if not new_ok:
                failures += 1
            mark = "ok" if new_ok else "FAIL"
            print(
                f"{dname:20}{iname:18}{sh:>10}{old_top:>10.0f}{new_top:>10.0f}{mark:>6}"
            )

    print("-" * 74)
    total = len(DEVICES) * len(IMAGES)
    print(f"old layout: {old_failures}/{total} combinations pushed buttons off-screen")
    print(f"fixed layout: {failures}/{total} off-screen")

    if failures:
        print("\nFAIL: buttons still unreachable in some configuration")
        raise SystemExit(1)
    if old_failures == 0:
        print("\nFAIL: test did not reproduce the original bug — it proves nothing")
        raise SystemExit(1)
    print("\nAction row is reachable on every device/image combination.")


if __name__ == "__main__":
    main()
