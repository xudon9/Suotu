#!/usr/bin/env python3
"""
Find the cheapest (width, quality) that keeps screenshot text READABLE.

The previous sweep showed the byte budgets never bind for UI screenshots — quality
pinned at 95 and still came in far under budget. So the real question is not "how
few bytes" but "how few bytes while the text still reads". We measure that directly
with OCR instead of guessing:

  render a known English string -> scale -> encode -> OCR -> compare to ground truth

Accuracy is character-level similarity against the known text. The cheapest setting
that stays above the accuracy floor is the one worth shipping.
"""
import subprocess
import os
import tempfile
import difflib

WORK = tempfile.mkdtemp(prefix="suotu-ocr-")
WIDTHS = [480, 560, 640, 720, 800, 900, 1080]
QUALITIES = [40, 50, 60, 70, 80, 90]
ACCURACY_FLOOR = 0.97

# Ground truth: the kind of dense English text that appears in app screenshots.
LINES = [
    "Settings",
    "Wi-Fi Connected to HomeNetwork",
    "Bluetooth On, 2 devices",
    "Mobile Network 4.2 GB used",
    "Display Adaptive brightness",
    "Sound and Vibration Ring 60%",
    "Notifications 14 apps allowed",
    "Battery 87% - 6h 20m left",
    "Storage 64.2 GB of 128 GB used",
    "Privacy Permission manager",
    "Security Screen lock enabled",
    "Apps 142 installed",
    "System Android 16",
    "About phone Build SQ3A.260915",
]
GROUND_TRUTH = "\n".join(LINES)


def run(cmd):
    subprocess.run(cmd, check=True, capture_output=True)


def find_font():
    try:
        return subprocess.run(
            ["fc-match", "-f", "%{file}", "DejaVu Sans"],
            capture_output=True, text=True, check=True).stdout.strip()
    except Exception:
        return None


FONT = find_font()


def make_source(path):
    """1260x2800 settings-style page at native phone resolution."""
    a = ["magick", "-size", "1260x2800", "xc:white"]
    y = 160
    for line in LINES:
        a += ["-fill", "#111", "-pointsize", "40"]
        if FONT:
            a += ["-font", FONT]
        a += ["-annotate", f"+80+{y}", line]
        a += ["-fill", "#e0e0e0", "-draw", f"rectangle 80,{y + 30} 1180,{y + 31}"]
        y += 190
    a += ["-quality", "100", path]
    run(a)


def ocr(path):
    base = os.path.join(WORK, "ocrout")
    subprocess.run(["tesseract", path, base, "--psm", "6", "-l", "eng"],
                   check=True, capture_output=True)
    with open(base + ".txt", encoding="utf-8", errors="replace") as f:
        return f.read()


def normalize(text):
    # Compare on content, not on whitespace/case noise.
    return " ".join(text.split()).lower()


def accuracy(text):
    return difflib.SequenceMatcher(
        None, normalize(GROUND_TRUTH), normalize(text)).ratio()


def main():
    src = os.path.join(WORK, "src.png")
    make_source(src)
    print(f"source: 1260x2800  {os.path.getsize(src) / 1024:.0f} KB")
    print(f"OCR accuracy floor: {ACCURACY_FLOOR:.2f}\n")

    # Sanity check: the untouched source should OCR near-perfectly, otherwise the
    # metric itself is broken and every downstream number is meaningless.
    base_acc = accuracy(ocr(src))
    print(f"baseline (original PNG) accuracy = {base_acc:.3f}")
    if base_acc < 0.95:
        print("!! OCR baseline is poor; treat results as unreliable")
    print()

    header = f"{'width':>6} | " + " | ".join(f"q{q:<2}" for q in QUALITIES)
    print(header)
    print("-" * len(header))

    passing = []
    for w in WIDTHS:
        scaled = os.path.join(WORK, f"s{w}.png")
        run(["magick", src, "-resize", f"{w}x>", scaled])
        cells = []
        for q in QUALITIES:
            out = os.path.join(WORK, f"t{w}_{q}.webp")
            run(["magick", scaled, "-quality", str(q),
                 "-define", "webp:method=4", "-strip", out])
            size = os.path.getsize(out)
            acc = accuracy(ocr(out))
            ok = acc >= ACCURACY_FLOOR
            if ok:
                passing.append((size, w, q, acc))
            cells.append(f"{'OK ' if ok else '   '}{size / 1024:>3.0f}K")
        print(f"{w:>6} | " + " | ".join(cells))

    print("\nCheapest settings meeting the accuracy floor:")
    for size, w, q, acc in sorted(passing)[:8]:
        print(f"  {w:>4}px q{q:<3} {size / 1024:>5.0f} KB   acc={acc:.3f}")

    print(f"\nArtifacts in {WORK}")


if __name__ == "__main__":
    main()
