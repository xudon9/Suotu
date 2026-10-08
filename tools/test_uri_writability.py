#!/usr/bin/env python3
"""
Check which uris the app is willing to overwrite, and why.

The bug this guards against: `isMediaStoreUri` compared only the AUTHORITY against
"media". The system photo picker's uris have that same authority —
content://media/picker/0/<pkg>/media/<id> — so they passed the check, and the write then
failed with "PhotoPicker Uri can only be accessed by ...". The picker grants read access
only, by design: it exists so an app can read a photo WITHOUT holding the media
permission, which is precisely what makes it unusable as a write target.

Two things are being tested here, and they are different questions:

  is a uri writable AS IS   -> authority is media, and it is not a picker uri
  can a writable row be FOUND behind it -> only for picker uris, and only with the
                                           read permission, by taking the trailing path
                                           segment as the MediaStore row id
"""

MEDIA = "media"


def classify(uri):
    """Split a content uri into (scheme, authority, path segments)."""
    if not uri.startswith("content://"):
        return uri.split(":", 1)[0], None, []
    rest = uri[len("content://"):]
    authority, _, path = rest.partition("/")
    return "content", authority, [p for p in path.split("/") if p]


def is_picker(uri):
    scheme, authority, segments = classify(uri)
    return scheme == "content" and authority == MEDIA and segments[:1] == ["picker"]


def is_media_store(uri):
    scheme, authority, _ = classify(uri)
    return scheme == "content" and authority == MEDIA and not is_picker(uri)


def writable_uri_for(uri, has_read_permission):
    """
    Mirror of OriginalReplacer.writableUriFor.

    Returns "as-is", a resolved row uri, or None.
    """
    if not is_picker(uri):
        return "as-is" if is_media_store(uri) else None
    if not has_read_permission:
        return None
    _, _, segments = classify(uri)
    if not segments or not segments[-1].isdigit():
        return None
    return f"content://media/external/images/media/{segments[-1]}"


CASES = [
    # (uri, description, writable as-is, resolved when permitted, resolved when not)
    (
        "content://media/external/images/media/1234",
        "ordinary gallery image",
        True, "as-is", "as-is",
    ),
    (
        "content://media/picker/0/com.android.providers.media.photopicker/media/1000000033",
        "system photo picker",
        False, "content://media/external/images/media/1000000033", None,
    ),
    (
        "content://media/picker/0/com.android.providers.media.photopicker/media/42",
        "picker, small id",
        False, "content://media/external/images/media/42", None,
    ),
    (
        "content://com.other.app.provider/file/some-id",
        "another app's share uri",
        False, None, None,
    ),
    (
        "content://com.android.providers.downloads.documents/document/msf%3A42",
        "a downloaded document",
        False, None, None,
    ),
    (
        "file:///sdcard/Pictures/x.png",
        "a bare file uri",
        False, None, None,
    ),
    (
        "content://media/external/images/media/not-a-number",
        "malformed media row",
        True, "as-is", "as-is",
    ),
]


def main():
    failures = 0

    print("=== classification ===")
    print(f"{'uri':56}{'picker':>8}{'media':>7}")
    print("-" * 71)
    for uri, label, writable, _, _ in CASES:
        short = uri if len(uri) <= 54 else uri[:51] + "..."
        print(f"{short:56}{str(is_picker(uri)):>8}{str(is_media_store(uri)):>7}")
    print()
    for uri, label, _, _, _ in CASES:
        print(f"  {label}")

    print("\n=== writable as-is ===")
    for uri, label, writable, _, _ in CASES:
        got = is_media_store(uri)
        ok = got == writable
        if not ok:
            failures += 1
        print(f"  {label:34} {str(got):>5}   {'ok' if ok else 'FAIL'}")

    print("\n=== resolution, with read permission ===")
    for uri, label, _, with_perm, _ in CASES:
        got = writable_uri_for(uri, True)
        ok = got == with_perm
        if not ok:
            failures += 1
        shown = got if got else "None"
        print(f"  {label:34} {str(shown)[:44]:>44}   {'ok' if ok else 'FAIL'}")

    print("\n=== resolution, WITHOUT read permission ===")
    for uri, label, _, _, without_perm in CASES:
        got = writable_uri_for(uri, False)
        ok = got == without_perm
        if not ok:
            failures += 1
        print(f"  {label:34} {str(got):>44}   {'ok' if ok else 'FAIL'}")

    print("\n=== the specific bug: a picker uri must never be written directly ===")
    picker = (
        "content://media/picker/0/"
        "com.android.providers.media.photopicker/media/1000000033"
    )
    ok = not is_media_store(picker)
    if not ok:
        failures += 1
    print(f"  picker uri accepted as writable: {is_media_store(picker)}  "
          f"{'ok' if ok else 'FAIL'}")

    # And the resolution must not invent a row for a picker uri with a non-numeric tail.
    junk = "content://media/picker/0/com.android.providers.media.photopicker/media/x"
    ok = writable_uri_for(junk, True) is None
    if not ok:
        failures += 1
    print(f"  non-numeric picker id resolves to None: {ok}  "
          f"{'ok' if ok else 'FAIL'}")

    print()
    if failures:
        print(f"FAIL: {failures} check(s) failed")
        raise SystemExit(1)
    print("Uri writability is classified correctly.")


if __name__ == "__main__":
    main()
