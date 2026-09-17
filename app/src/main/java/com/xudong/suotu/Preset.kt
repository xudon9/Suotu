package com.xudong.suotu

import androidx.annotation.StringRes

/**
 * A shrink preset: how wide the result may be, and how many bytes it may take.
 *
 * Width is expressed in pixels rather than as a percentage of the source on purpose.
 * Text readability depends on the pixel height of the glyphs, so a fixed 40% is far too
 * aggressive for a full-screen 1260px capture and meaningless for a small crop.
 * Targeting an absolute width makes the result predictable regardless of the crop.
 *
 * The numbers come from an OCR sweep (tools/threshold_sweep.py) rather than taste:
 *
 *   - Text stays legible down to roughly a 10px glyph height; below that it rots fast.
 *   - The SMALLEST text in the shot binds. On a 1260-wide phone screenshot captions run
 *     ~22px, so the usable floor is about 1260 * (10/22) ≈ 570px.
 *   - Quality barely matters for text (q40 and q90 scored identically), so presets
 *     differ mainly in width; the byte budget is a safety net for photo-like content.
 */
enum class Preset(
    @StringRes val labelRes: Int,
    @StringRes val hintRes: Int,
    val targetWidth: Int,
    val budgetBytes: Int,
) {
    /** For shots whose text is already large — a dialog, a headline, a photo. */
    TINY(R.string.preset_tiny, R.string.hint_tiny, 540, 60 * 1024),

    /** Default: just above the measured floor for a full-screen 1260px capture. */
    NORMAL(R.string.preset_normal, R.string.hint_normal, 720, 120 * 1024),

    /** Keeps dense content (code, tables, long chat logs) comfortably readable. */
    SHARP(R.string.preset_sharp, R.string.hint_sharp, 960, 250 * 1024),
    ;

    companion object {
        val DEFAULT = NORMAL

        fun fromName(name: String?): Preset =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/** Which container formats the engine is allowed to emit. */
enum class FormatPolicy(@StringRes val labelRes: Int) {
    /**
     * Try WebP and JPEG, keep the better result. Measurement showed WebP landing at
     * roughly half the size of JPEG at equal legibility, so this usually picks WebP.
     */
    AUTO(R.string.format_auto),

    /**
     * Force WebP — usually about half the size of JPEG at equal legibility.
     *
     * AUTO nearly always lands on WebP anyway, but naming it explicitly is worth a chip:
     * it makes the format predictable when the recipient is known to support it.
     */
    WEBP_ONLY(R.string.format_webp),

    /** Force JPEG — maximum compatibility with older clients. */
    JPEG_ONLY(R.string.format_jpeg),

    /**
     * Force PNG — lossless, so no compression artefacts at all.
     *
     * Deliberately not part of AUTO: PNG is typically several times larger than a
     * lossy encode of the same screenshot, so including it in a "smallest wins" search
     * would mean it never won and the option would be unreachable. It exists for when
     * pixel-exactness matters more than size.
     */
    PNG_ONLY(R.string.format_png),
    ;

    companion object {
        val DEFAULT = AUTO

        fun fromName(name: String?): FormatPolicy =
            entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
