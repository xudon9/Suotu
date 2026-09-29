#!/usr/bin/env python3
"""
Check the string resources for the two mistakes that are easy to make and slow to find.

Duplicates: a repeated <string name="x"> is a HARD build failure, but the error names
the resource rather than the file and line, and only surfaces at packaging time — after
compilation has already succeeded, which makes it look like a packaging problem rather
than a typo. This happened while adding the notification action buttons: a name collided
with a stale string from an earlier iteration.

Missing: a R.string.foo used in Kotlin with no definition. Kotlin compilation catches
this one, but only for the default locale — a key present in values/ and absent from
values-zh/ compiles fine and silently falls back to English for Chinese users, which is
the kind of gap nobody notices until someone reads the Chinese UI.
"""

import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
RES = ROOT / "app/src/main/res"
SRC = ROOT / "app/src/main/java/com/xudong/suotu"


def string_names(path):
    return re.findall(r'<string name="([^"]+)"', path.read_text())


def main():
    failures = 0

    print("=== no duplicate string names within a file ===")
    for f in sorted(RES.glob("values*/strings.xml")):
        names = string_names(f)
        dupes = sorted({n for n in names if names.count(n) > 1})
        rel = f.relative_to(ROOT)
        if dupes:
            failures += 1
            print(f"  FAIL {rel}: {', '.join(dupes)}")
        else:
            print(f"  ok   {rel} ({len(names)} strings)")

    print("\n=== both locales define the same keys ===")
    en_file = RES / "values/strings.xml"
    zh_file = RES / "values-zh/strings.xml"
    en = set(string_names(en_file))
    zh = set(string_names(zh_file))

    only_en = sorted(en - zh)
    only_zh = sorted(zh - en)
    if only_en:
        failures += 1
        print(f"  FAIL English-only (Chinese users would see English): {only_en}")
    if only_zh:
        failures += 1
        print(f"  FAIL Chinese-only: {only_zh}")
    if not only_en and not only_zh:
        print(f"  ok   {len(en)} keys, identical in both locales")

    print("\n=== every R.string used in Kotlin is defined ===")
    used = set()
    for kt in SRC.glob("*.kt"):
        used |= set(re.findall(r"R\.string\.([A-Za-z0-9_]+)", kt.read_text()))

    missing = sorted(used - en)
    if missing:
        failures += 1
        print(f"  FAIL used but undefined: {missing}")
    else:
        print(f"  ok   {len(used)} referenced strings, all defined")

    # The reverse direction is reported, not failed: a few strings are legitimately
    # reached only through layout XML or kept for a surface not yet wired up.
    unused = sorted(en - used)
    if unused:
        print(f"\n  note: {len(unused)} defined but not referenced from Kotlin:")
        for u in unused:
            print(f"        {u}")

    print()
    if failures:
        print(f"FAIL: {failures} check(s) failed")
        raise SystemExit(1)
    print("String resources are consistent.")


if __name__ == "__main__":
    main()
