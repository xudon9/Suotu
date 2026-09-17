#!/usr/bin/env python3
"""
Mirror of WatchRule.matches() + MediaQuery.isOwnOutput(), exercised against the cases
that actually matter on the phone.

The point is the loop guard: the background watcher writes its output into the gallery,
which is the very thing that wakes it. If a broad rule (say "Pictures" + any image) also
matched that output, the app would shrink its own result forever. This checks that the
guard holds even for rules aimed straight at the output folder.
"""
import re

ALBUM = "Suotu"
SMALL_SUFFIX = "_small"

SCREENSHOT_REGEX = r"^Screenshot_\d{8}_\d{6}\.(jpg|jpeg|png|webp)$"
ANY_IMAGE_REGEX = r"^.*\.(jpg|jpeg|png|webp)$"


def rule_matches(rule, rel_path, name):
    path, pattern, enabled = rule
    if not enabled:
        return False
    try:
        if not re.fullmatch(pattern, name, re.IGNORECASE):
            return False
    except re.error:
        return False
    actual = (rel_path or "").strip("/")
    if not actual:
        return False
    norm = path.strip("/")
    return actual.lower() == norm.lower() or actual.lower().startswith(norm.lower() + "/")


def is_own_output(rel_path, name):
    in_album = ALBUM.lower() in (rel_path or "").strip("/").lower()
    named = SMALL_SUFFIX.lower() in name.lower()
    return in_album or named


def accepted(rules, rel_path, name):
    """Full pipeline: loop guard first, then rules."""
    if is_own_output(rel_path, name):
        return False
    return any(rule_matches(r, rel_path, name) for r in rules)


AUTO_RULES = [("Pictures/Screenshots", SCREENSHOT_REGEX, True)]
OPEN_RULES = [
    ("Pictures/Screenshots", SCREENSHOT_REGEX, True),
    ("DCIM/Camera", ANY_IMAGE_REGEX, True),
]
# The dangerous one: a broad rule that also covers the output album.
GREEDY_RULES = [("Pictures", ANY_IMAGE_REGEX, True)]

CASES = [
    # (rel_path, name, auto?, open?, greedy?) — expectations
    # NOTE: the greedy "Pictures" rule matches by PREFIX, so it legitimately covers
    # Pictures/Screenshots too. That is intended: it is what makes the loop guard
    # (rather than the rules) the thing that must keep the output out.
    ("Pictures/Screenshots/", "Screenshot_20260916_110032.jpg", True, True, True),
    ("Pictures/Screenshots/", "Screenshot_20260916_110032.png", True, True, True),
    ("Pictures/Screenshots/", "IMG_20260916_085937.jpg", False, False, True),
    ("Pictures/Screenshots/", "Screenrecording_20260916_000943.mp4", False, False, False),
    ("DCIM/Camera/", "IMG_20260916_085937.jpg", False, True, False),
    ("DCIM/Camera/", "Screenshot_20260916_110032.jpg", False, True, False),
    # Loop guards — must NEVER be accepted, even by the greedy rule.
    ("Pictures/Suotu/", "Screenshot_20260916_110032_small.webp", False, False, False),
    ("Pictures/Suotu/", "Screenshot_20260916_110032.jpg", False, False, False),
    ("Pictures/Screenshots/", "Screenshot_20260916_110032_small.jpg", False, False, False),
    # Greedy rule should still take ordinary Pictures files.
    ("Pictures/", "photo.jpg", False, False, True),
    ("Pictures/Wallpapers/", "wall.png", False, False, True),
    # Edge: empty path cannot be confirmed -> reject.
    ("", "Screenshot_20260916_110032.jpg", False, False, False),
    (None, "Screenshot_20260916_110032.jpg", False, False, False),
]

def main():
    print(f"{'path':28}{'name':46}{'auto':>6}{'open':>6}{'greedy':>8}")
    print("-" * 94)
    failures = 0
    for rel, name, exp_a, exp_o, exp_g in CASES:
        got_a = accepted(AUTO_RULES, rel, name)
        got_o = accepted(OPEN_RULES, rel, name)
        got_g = accepted(GREEDY_RULES, rel, name)

        ok = (got_a, got_o, got_g) == (exp_a, exp_o, exp_g)
        if not ok:
            failures += 1

        def cell(got, exp):
            return ("Y" if got else "n") + ("" if got == exp else "!")

        mark = "" if ok else "   <-- MISMATCH"
        print(f"{str(rel):28}{name:46}{cell(got_a, exp_a):>6}"
              f"{cell(got_o, exp_o):>6}{cell(got_g, exp_g):>8}{mark}")

    print("-" * 94)
    if failures:
        print(f"FAIL: {failures} case(s) did not match expectations")
        raise SystemExit(1)
    print("All cases behave as intended.")
    print("Loop guard holds: output is rejected even by a rule pointed at its folder.")


if __name__ == "__main__":
    main()
