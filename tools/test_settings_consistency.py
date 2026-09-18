#!/usr/bin/env python3
"""
Check that the background watcher and the interactive screen use the SAME settings.

The bug this guards against: the watcher called a preset-based overload of
ShrinkEngine.shrink, so it ignored the width slider and the quality override entirely
and always emitted the default preset's 720px. Setting the width to 400px changed the
interactive result and silently did nothing to the background one.

That is a nasty class of bug because nothing looks broken — both paths work, they just
disagree — so this checks the source directly rather than the behaviour.
"""

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "app/src/main/java/com/xudong/suotu"


def read(name):
    return (SRC / name).read_text()


def main():
    failures = 0

    engine = read("ShrinkEngine.kt")
    watcher = read("ScreenshotWatcherJob.kt")
    activity = read("ShrinkActivity.kt")

    print("=== the engine exposes exactly one shrink() entry point ===")
    # A second overload is what let the two callers drift apart.
    signatures = re.findall(r"\n    fun shrink\(", engine)
    print(f"  public shrink() overloads: {len(signatures)}")
    if len(signatures) != 1:
        print("  FAIL: more than one entry point invites the paths to diverge")
        failures += 1
    else:
        print("  ok")

    print("\n=== neither caller passes a Preset ===")
    for name, text in (("ScreenshotWatcherJob", watcher), ("ShrinkActivity", activity)):
        uses_preset = re.search(r"shrink\([^)]*settings\.preset", text, re.S)
        print(f"  {name}: {'FAIL — passes settings.preset' if uses_preset else 'ok'}")
        if uses_preset:
            failures += 1

    print("\n=== both callers read the same settings ===")
    # The settings that must reach BOTH paths, and how to spot them in a call.
    required = {
        "width": r"targetWidth\s*=\s*settings\.outputWidth|targetWidth\s*=\s*width",
        "format": r"formatPolicy\s*=\s*settings\.formatPolicy|formatPolicy\s*=\s*policy",
        "quality": r"forcedQuality\s*=\s*settings\.manualQuality|"
                   r"forcedQuality\s*=\s*manualQuality",
        "budget": r"budgetBytes\s*=\s*SizeRange\.budgetFor",
    }
    print(f"{'setting':>10}  {'watcher':>9}  {'activity':>9}")
    print("  " + "-" * 32)
    for label, pattern in required.items():
        in_watcher = bool(re.search(pattern, watcher))
        in_activity = bool(re.search(pattern, activity))
        ok = in_watcher and in_activity
        if not ok:
            failures += 1
        print(f"{label:>10}  {str(in_watcher):>9}  {str(in_activity):>9}"
              f"{'' if ok else '   <-- FAIL'}")

    print("\n=== Settings has no stale preset property ===")
    settings = read("Settings.kt")
    stale = "var preset:" in settings
    print(f"  Settings.preset present: {stale}")
    if stale:
        print("  FAIL: a leftover preset setting is a trap for the next caller")
        failures += 1
    else:
        print("  ok — outputWidth is the single source of truth")

    print()
    if failures:
        print(f"FAIL: {failures} check(s) failed")
        raise SystemExit(1)
    print("Both shrink paths honour the same user settings.")


if __name__ == "__main__":
    main()
