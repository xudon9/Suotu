#!/usr/bin/env python3
"""
Validate Suotu's preset budgets against realistic screenshots.

Mirrors ShrinkEngine: scale to target width (never upscale), then binary-search
encoder quality in [35,95] to find the highest quality fitting the byte budget.
Uses ImageMagick, close enough to Android's libjpeg/libwebp to be indicative.

Builds argv lists directly (no shell) so text with quotes/CJK is safe.
"""
import subprocess
import os
import tempfile

PRESETS = [
    ("TINY",   640,  80 * 1024),
    ("NORMAL", 800, 150 * 1024),
    ("SHARP", 1080, 300 * 1024),
]
MIN_Q, MAX_Q, STEPS = 35, 95, 6
WORK = tempfile.mkdtemp(prefix="suotu-")

# A CJK-capable font, resolved once.
def find_cjk_font():
    try:
        out = subprocess.run(["fc-match", "-f", "%{file}", "Noto Sans CJK SC"],
                             capture_output=True, text=True, check=True).stdout.strip()
        if out:
            return out
    except Exception:
        pass
    return None

FONT = find_cjk_font()


def run(cmd):
    subprocess.run(cmd, check=True, capture_output=True)


def text_args(size, color, x, y, text):
    args = ["-fill", color, "-pointsize", str(size)]
    if FONT:
        args += ["-font", FONT]
    args += ["-annotate", f"+{x}+{y}", text]
    return args


def make_chat_screenshot(path):
    """WeChat-like chat: many small text rows. The hard case for compression."""
    a = ["magick", "-size", "1260x2800", "xc:#ededed"]
    a += ["-fill", "#f7f7f7", "-draw", "rectangle 0,0 1260,90"]
    a += text_args(30, "#111", 40, 58, "09:41")
    a += text_args(30, "#111", 1100, 58, "100%")
    a += ["-fill", "#ededed", "-draw", "rectangle 0,90 1260,200"]
    a += text_args(42, "#000", 480, 160, "项目讨论组")

    msgs = [
        (0, "明天的会议改到下午三点了"),
        (1, "收到，我把日程更新一下"),
        (0, "另外这个接口的返回值需要确认"),
        (1, "好的，我下午看一下代码"),
        (0, "The deployment pipeline failed again"),
        (1, "Checking the logs now - looks like a timeout"),
        (0, "设计稿已经上传到共享文件夹"),
        (1, "OK, I'll review it tonight"),
        (0, "记得提交周报"),
        (1, "已经发了，在邮件里"),
    ]
    y = 250
    for side, text in msgs:
        # CJK glyphs are full-width; approximate advance per char.
        adv = 38 if any(ord(c) > 0x2E80 for c in text) else 20
        w = min(40 + len(text) * adv, 900)
        if side == 0:
            a += ["-fill", "white", "-draw",
                  f"roundrectangle 120,{y} {120 + w},{y + 110} 16,16"]
            a += text_args(36, "#000", 150, y + 72, text)
        else:
            x0 = 1140 - w
            a += ["-fill", "#95ec69", "-draw",
                  f"roundrectangle {x0},{y} 1140,{y + 110} 16,16"]
            a += text_args(36, "#000", x0 + 30, y + 72, text)
        y += 150

    a += ["-quality", "100", path]
    run(a)


def make_ui_screenshot(path):
    """Settings-like page: flat colors, sharp separators."""
    a = ["magick", "-size", "1260x2800", "xc:white"]
    a += ["-fill", "#f2f2f7", "-draw", "rectangle 0,0 1260,200"]
    a += text_args(54, "#000", 60, 150, "Settings")
    rows = ["Wi-Fi", "Bluetooth", "Mobile Network", "Display", "Sound & Vibration",
            "Notifications", "Battery", "Storage", "Privacy", "Security",
            "Apps", "System", "About phone", "Developer options"]
    y = 240
    for r in rows:
        a += ["-fill", "white", "-draw", f"rectangle 0,{y} 1260,{y + 160}"]
        a += text_args(40, "#111", 80, y + 100, r)
        a += ["-fill", "#d0d0d0", "-draw", f"rectangle 80,{y + 159} 1260,{y + 160}"]
        y += 170
    a += ["-quality", "100", path]
    run(a)


def make_photo_screenshot(path):
    """Photo-ish content: gradients + noise. Where JPEG/WebP shine."""
    a = ["magick", "-size", "1260x2800", "gradient:#1e3c72-#2a5298",
         "-attenuate", "0.6", "+noise", "Gaussian",
         "-blur", "0x2"]
    a += text_args(64, "white", 80, 200, "Photo-like content")
    a += ["-quality", "100", path]
    run(a)


def encode(scaled, fmt, q, out):
    if fmt == "jpg":
        run(["magick", scaled, "-quality", str(q),
             "-sampling-factor", "4:2:0", "-strip", out])
    else:
        run(["magick", scaled, "-quality", str(q),
             "-define", "webp:method=4", "-strip", out])
    return os.path.getsize(out)


def search(scaled, fmt, budget):
    """Same binary search as ShrinkEngine.searchQuality."""
    lo, hi = MIN_Q, MAX_Q
    best = None
    smallest = None
    out = os.path.join(WORK, f"probe.{fmt}")
    for _ in range(STEPS):
        if lo > hi:
            break
        mid = (lo + hi) // 2
        size = encode(scaled, fmt, mid, out)
        if smallest is None or size < smallest[1]:
            smallest = (mid, size)
        if size <= budget:
            if best is None or mid > best[0]:
                best = (mid, size)
            lo = mid + 1
        else:
            hi = mid - 1
    return best, smallest


def main():
    if FONT:
        print(f"font: {FONT}")
    scenarios = [
        ("chat", make_chat_screenshot),
        ("ui", make_ui_screenshot),
        ("photo", make_photo_screenshot),
    ]
    for name, maker in scenarios:
        src = os.path.join(WORK, f"{name}.png")
        maker(src)
        orig = os.path.getsize(src)
        print(f"\n{'=' * 70}")
        print(f"SOURCE: {name}.png  1260x2800  {orig / 1024:.0f} KB (PNG)")
        print('=' * 70)
        print(f"{'preset':8}{'width':>6}{'budget':>9}{'fmt':>6}{'q':>5}"
              f"{'size':>10}{'fits':>6}{'winner':>8}")
        print('-' * 70)
        for pname, tw, budget in PRESETS:
            scaled = os.path.join(WORK, f"{name}-{tw}.png")
            run(["magick", src, "-resize", f"{tw}x>", scaled])
            results = {}
            for fmt in ("webp", "jpg"):
                best, smallest = search(scaled, fmt, budget)
                results[fmt] = (best, smallest)

            # Same pickBetter rule as the engine.
            def key(item):
                fmt, (best, smallest) = item
                if best:
                    return (0, -best[0])
                return (1, smallest[1])
            winner = min(results.items(), key=key)[0]

            for fmt in ("webp", "jpg"):
                best, smallest = results[fmt]
                if best:
                    q, size = best
                    fits = "yes"
                else:
                    q, size = smallest
                    fits = "NO"
                mark = "<<<" if fmt == winner else ""
                print(f"{pname:8}{tw:>6}{budget // 1024:>7}KB{fmt:>6}{q:>5}"
                      f"{size / 1024:>8.0f}KB{fits:>6}{mark:>8}")
        print('-' * 70)

    print(f"\nArtifacts in {WORK}")


if __name__ == "__main__":
    main()
