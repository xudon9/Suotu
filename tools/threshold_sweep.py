#!/usr/bin/env python3
"""
Find where small screenshot text ACTUALLY breaks under scaling.

The first sweep was uninformative: 40px source text stayed 100% readable even at
480px wide. Real screenshots are not made of 40px text — they contain timestamps,
captions and secondary labels at 22-30px on a 1260-wide panel. Those are what
decide whether a shrunk screenshot is usable.

This sweep therefore varies the SOURCE text size and reports, for each, the
smallest output width that still OCRs cleanly. The output is a rule of the form
"keep glyphs at least N pixels tall", which is what the app should encode.
"""
import subprocess
import os
import tempfile
import difflib

WORK = tempfile.mkdtemp(prefix="suotu-thr-")
# Source text sizes on a 1260-wide panel: caption -> body -> title.
TEXT_SIZES = [22, 26, 30, 34, 40]
WIDTHS = [420, 480, 540, 600, 660, 720, 800, 900, 1080, 1260]
QUALITY = 75
ACCURACY_FLOOR = 0.97
SRC_W = 1260

LINES = [
    "Wi-Fi Connected to HomeNetwork 5G",
    "Bluetooth On, 2 devices paired",
    "Mobile data 4.2 GB used this month",
    "Battery 87 percent, 6h 20m left",
    "Storage 64.2 GB of 128 GB used",
    "Updated 15 minutes ago at 09:41",
    "Build SQ3A.260915.003 rev 12",
    "Screen lock enabled, PIN required",
]
GROUND_TRUTH = "\n".join(LINES)


def run(cmd):
    subprocess.run(cmd, check=True, capture_output=True)


FONT = subprocess.run(["fc-match", "-f", "%{file}", "DejaVu Sans"],
                      capture_output=True, text=True).stdout.strip() or None


def make_source(path, pointsize):
    a = ["magick", "-size", f"{SRC_W}x{len(LINES) * pointsize * 3 + 100}", "xc:white"]
    y = pointsize * 2
    for line in LINES:
        a += ["-fill", "#111", "-pointsize", str(pointsize)]
        if FONT:
            a += ["-font", FONT]
        a += ["-annotate", f"+60+{y}", line]
        y += pointsize * 3
    a += ["-quality", "100", path]
    run(a)


def ocr(path):
    base = os.path.join(WORK, "o")
    subprocess.run(["tesseract", path, base, "--psm", "6", "-l", "eng"],
                   check=True, capture_output=True)
    with open(base + ".txt", encoding="utf-8", errors="replace") as f:
        return f.read()


def acc(text):
    n = lambda s: " ".join(s.split()).lower()
    return difflib.SequenceMatcher(None, n(GROUND_TRUTH), n(text)).ratio()


def main():
    print(f"quality={QUALITY} (WebP), accuracy floor={ACCURACY_FLOOR}")
    print(f"source panel width={SRC_W}\n")

    print(f"{'src text':>9} {'min width':>10} {'scale':>7} {'glyph px':>9} "
          f"{'size':>7} {'acc':>6}")
    print("-" * 56)

    recommendations = []
    for ts in TEXT_SIZES:
        src = os.path.join(WORK, f"src{ts}.png")
        make_source(src, ts)

        baseline = acc(ocr(src))
        if baseline < 0.95:
            print(f"{ts:>7}px  baseline OCR only {baseline:.2f} - skipping")
            continue

        found = None
        for w in WIDTHS:
            scaled = os.path.join(WORK, f"s{ts}_{w}.webp")
            run(["magick", src, "-resize", f"{w}x>", "-quality", str(QUALITY),
                 "-define", "webp:method=4", "-strip", scaled])
            a = acc(ocr(scaled))
            if a >= ACCURACY_FLOOR:
                scale = w / SRC_W
                found = (w, scale, ts * scale, os.path.getsize(scaled), a)
                break

        if found:
            w, scale, glyph, size, a = found
            print(f"{ts:>7}px {w:>9}px {scale:>6.0%} {glyph:>8.1f}px "
                  f"{size / 1024:>6.0f}K {a:>6.3f}")
            recommendations.append((ts, w, scale, glyph))
        else:
            print(f"{ts:>7}px  never reached the floor, even at full width")

    if recommendations:
        glyphs = [g for _, _, _, g in recommendations]
        print("-" * 56)
        print(f"\nMinimum readable glyph height observed: "
              f"{min(glyphs):.1f}px - {max(glyphs):.1f}px")
        print("\nImplication for the app:")
        print("  The binding constraint is GLYPH HEIGHT in the output, not a fixed")
        print("  scale percentage. A screenshot whose smallest text is Npx tall at")
        print("  1260 wide can be scaled by roughly (minGlyph / N) before text rots.")

    print(f"\nArtifacts in {WORK}")


if __name__ == "__main__":
    main()
