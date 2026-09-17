# 缩图 Suotu

Shrink Android screenshots before sharing them to WeChat / Telegram.

Replaces the manual loop of *crop → resize to ~40% → nudge JPEG quality until the
file is small but the text still reads* with two taps.

## Flow

1. Take a screenshot
2. Tap the preview, crop it in the **system screenshot editor** (Android's own)
3. **Share → 缩图**
4. Check the before/after numbers, tap **Send** → pick WeChat / Telegram

The app deliberately does *not* implement its own crop tool — Android's built-in
screenshot editor already does that well.

## How it decides

Two things are chosen for you:

**Width.** Presets target an absolute pixel width, not a percentage. Readability
depends on the pixel height of the glyphs, so a fixed 40% is far too aggressive for
a full 1260px-wide capture and meaningless for a small crop.

**Quality.** The encoder quality is binary-searched (6 steps) to land just under a
byte budget, for both WebP and JPEG; the better result wins.

| Preset | Width | Budget | Use for |
|--------|-------|--------|---------|
| 最小 Tiny | 540px | 60 KB | big text, photos |
| 标准 Normal | 720px | 120 KB | **default** — safe for full screenshots |
| 清晰 Sharp | 960px | 250 KB | dense text, code, tables |

## Watch rules (configurable)

Two **independent** lists of `(folder, filename regex)` rules, because the folders worth
shrinking unattended are not the folders worth offering on demand:

| | Folders to watch | Folders to offer |
|---|---|---|
| When | Silently, in the background | When you open the app |
| Default | `Pictures/Screenshots` only | Screenshots **and** `DCIM/Camera` |
| Why | Every match is shrunk without asking — you do not want a small copy of every photo you take | Nothing is written unless you tap Send, so a wider net is harmless |

Folder matching is by **prefix**, so `DCIM` also covers `DCIM/Camera`. The regex is
matched against the **file name** only, and an invalid one is flagged in the editor
rather than silently matching nothing.

### Opening behaviour

On launch the app looks for the newest image matching the *offer* rules:

- Newer than the threshold (default **60s**, configurable) → loaded automatically.
- Otherwise → an **Open an image** button (system picker, needs no permission).

### Not eating its own output

The watcher is woken by gallery changes and also writes to the gallery, so a broad rule
like `Pictures` + any image would otherwise shrink its own result forever. Three guards
prevent that, verified by `tools/test_rule_matching.py`:

1. Anything under the `Suotu` album is rejected outright.
2. Any name containing `_small` is rejected.
3. A per-name dedup memory stops repeat triggers for the same file.

## Why those numbers

They come from measurement, not taste. The scripts in `tools/` render text at known
sizes, scale and encode it, then OCR the result and score it against the ground
truth. Findings:

- **Text stays legible down to roughly a 10px glyph height**, then degrades quickly.
- **The smallest text in the shot is what binds.** On a 1260-wide phone screenshot
  captions run ~22px, giving a floor near `1260 × (10/22) ≈ 570px`. Hence 720px as
  the safe default.
- **Quality barely matters for UI text.** WebP q40 and q90 both scored 100% OCR
  accuracy, but q90 cost ~70% more bytes. `MAX_QUALITY` is therefore capped at 80 —
  otherwise "maximise quality within the budget" would always ship wasted bytes.
- **WebP is roughly half the size of JPEG** at equal legibility, so `Auto` normally
  picks WebP. Use `JPEG only` if a recipient's client is picky.

Reproduce:

```bash
python3 tools/validate_presets.py     # size per preset, per format
python3 tools/readability_sweep.py    # OCR accuracy across width × quality
python3 tools/threshold_sweep.py      # where small text actually breaks
python3 tools/test_rule_matching.py   # watch-rule matching + anti-loop guards
python3 tools/test_crop_geometry.py   # crop hit-testing: symmetric grab zones
python3 tools/test_crop_layout.py     # crop action row stays on screen
python3 tools/test_lasso_mapping.py   # letterbox-aware touch mapping
python3 tools/test_replace_naming.py  # extension follows the encoded format
python3 tools/test_annotation_geometry.py  # arrowheads + shape hit-testing
python3 tools/test_color_picker.py     # HSV conversion, alpha, transparency
```

## Caveat worth knowing

WeChat re-compresses images on send unless you tick **原图 / original**. The wins
here are therefore your *storage*, your *upload data*, and a predictable result —
not necessarily the recipient's download size. Send as a file to preserve it exactly.

## Build

Requires JDK 17+ and the Android SDK (`compileSdk` 37). Point `local.properties` at
your SDK, or set `ANDROID_HOME`:

```bash
echo "sdk.dir=$HOME/Android/Sdk" > local.properties

./gradlew :app:assembleRelease    # minified, signed with the debug key
./gradlew :app:assembleDebug      # unminified
```

Install:

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

The release build is signed with the standard Android **debug** key so it is directly
installable without provisioning a keystore. Replace `signingConfig` in
`app/build.gradle.kts` before distributing it anywhere real.

Notes:

- **AGP 9 has built-in Kotlin support.** Applying `org.jetbrains.kotlin.android`
  alongside it fails with *"extension with name 'kotlin' already registered"*. Only
  `com.android.application` + `org.jetbrains.kotlin.plugin.compose` are applied.
- `apksigner` needs `java` on `PATH` or it fails silently with no output.
- The release build is minified (R8) **and** signed with the debug keystore, so it is
  small and still directly installable.

## Layout

```
app/src/main/java/com/xudong/suotu/
  ShrinkActivity.kt   share-target entry point, Compose preview, re-share
  ShrinkEngine.kt     decode → EXIF rotate → scale → quality search → encode
  Preset.kt           the measured width/budget presets
  Settings.kt         remembers your last preset
tools/                the OCR experiments behind the numbers above
```

## License

MIT — see [LICENSE](LICENSE).

## Permissions

None. The app reads only the image handed to it by the share intent, and exposes its
output through a `FileProvider` scoped to `cache/shared`, which is swept after 24h.
