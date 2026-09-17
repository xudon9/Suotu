#!/usr/bin/env python3
"""
Check the file-name handling when replacing an original in place.

The trap: the shrunk bytes are often WebP while the original is a .jpg. Writing WebP
bytes under the old name leaves a file whose extension lies about its contents, which
some galleries and chat clients mishandle. So the name must follow the actual format,
and must NOT churn when the format already matches.
"""


def rename_for_format(name, fmt):
    """Mirror of OriginalReplacer.renameForFormat."""
    dot = name.rfind(".")
    stem = name[:dot] if dot > 0 else name
    ext = name[dot + 1:].lower() if dot > 0 else ""

    if fmt == "jpg":
        matches = ext in ("jpg", "jpeg")
    else:
        matches = ext == "webp"
    return name if matches else f"{stem}.{fmt}"


CASES = [
    # (original name, output format, expected new name)
    # Format changed: extension must follow the bytes.
    ("Screenshot_20260917_101501.jpg", "webp", "Screenshot_20260917_101501.webp"),
    ("IMG_20260917_085937.jpeg", "webp", "IMG_20260917_085937.webp"),
    ("photo.png", "jpg", "photo.jpg"),
    ("photo.png", "webp", "photo.webp"),

    # Already correct: must be left completely alone, no churn.
    ("Screenshot_20260917_101501.jpg", "jpg", "Screenshot_20260917_101501.jpg"),
    ("IMG_1234.jpeg", "jpg", "IMG_1234.jpeg"),
    ("shot.webp", "webp", "shot.webp"),

    # Case insensitivity: .JPG is still a JPEG, do not rename it.
    ("PHOTO.JPG", "jpg", "PHOTO.JPG"),
    ("PHOTO.JPEG", "jpg", "PHOTO.JPEG"),
    ("PHOTO.WEBP", "webp", "PHOTO.WEBP"),

    # A .png source re-encoded as a lossy format must take the new extension.
    ("shot.png", "webp", "shot.webp"),
    ("SHOT.PNG", "jpg", "SHOT.jpg"),

    # Awkward names that must not lose information.
    ("my.holiday.photo.jpg", "webp", "my.holiday.photo.webp"),
    ("no_extension", "webp", "no_extension.webp"),
    (".hidden", "webp", ".hidden.webp"),
    ("trailing.", "webp", "trailing.webp"),
]


def main():
    print(f"{'original':34}{'fmt':>6}{'result':>34}{'':>6}")
    print("-" * 80)
    failures = 0
    for name, fmt, want in CASES:
        got = rename_for_format(name, fmt)
        ok = got == want
        if not ok:
            failures += 1
        print(f"{name:34}{fmt:>6}{got:>34}{'ok' if ok else '  FAIL':>6}")
        if not ok:
            print(f"{'':40}want {want}")

    print("-" * 80)

    # The specific hazard: a .jpg left holding WebP bytes.
    lying = rename_for_format("Screenshot.jpg", "webp")
    if lying.endswith(".jpg"):
        print("FAIL: would leave WebP bytes in a .jpg file")
        failures += 1
    else:
        print(f"WebP bytes never stay in a .jpg (-> {lying})")

    # And the reverse hazard: needless renaming of a matching file.
    stable = rename_for_format("Screenshot.jpg", "jpg")
    if stable != "Screenshot.jpg":
        print("FAIL: renamed a file whose extension was already correct")
        failures += 1
    else:
        print("A correct extension is left untouched")

    if failures:
        print(f"\nFAIL: {failures} case(s) wrong")
        raise SystemExit(1)
    print("\nAll name cases behave correctly.")


if __name__ == "__main__":
    main()
