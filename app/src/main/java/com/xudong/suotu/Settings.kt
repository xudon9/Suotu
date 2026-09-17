package com.xudong.suotu

import android.content.Context

/** Persisted preferences. */
class Settings(context: Context) {

    private val prefs = context.getSharedPreferences("suotu", Context.MODE_PRIVATE)

    var preset: Preset
        get() = Preset.fromName(prefs.getString(KEY_PRESET, null))
        set(value) = prefs.edit().putString(KEY_PRESET, value.name).apply()

    var formatPolicy: FormatPolicy
        get() = FormatPolicy.fromName(prefs.getString(KEY_FORMAT, null))
        set(value) = prefs.edit().putString(KEY_FORMAT, value.name).apply()

    /**
     * Output width in pixels, set by the slider.
     *
     * Replaces the old three-preset choice; the presets survive as quick-jump chips.
     */
    var outputWidth: Int
        get() = prefs.getInt(KEY_WIDTH, SizeRange.DEFAULT_WIDTH)
        set(value) = prefs.edit()
            .putInt(KEY_WIDTH, value.coerceIn(SizeRange.MIN_WIDTH, SizeRange.MAX_WIDTH))
            .apply()

    /** Opt-in: watch for new files and save a shrunk copy automatically. */
    var autoShrinkEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO, value).apply()

    /** Whether the silent "copy ready" notification is posted. */
    var autoShrinkNotify: Boolean
        get() = prefs.getBoolean(KEY_AUTO_NOTIFY, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_NOTIFY, value).apply()

    /**
     * Folders watched by the SILENT background shrinker.
     *
     * Kept separate from [openRules] on purpose: the set of folders worth shrinking
     * unattended (screenshots) is much narrower than the set worth offering when the
     * app is opened deliberately (screenshots and camera).
     */
    var autoShrinkRules: List<WatchRule>
        get() = WatchRule.listFromJson(prefs.getString(KEY_AUTO_RULES, null))
            ?: WatchRule.defaultAutoShrinkRules()
        set(value) = prefs.edit()
            .putString(KEY_AUTO_RULES, WatchRule.listToJson(value)).apply()

    /** Folders consulted when the app is opened, to offer the newest image. */
    var openRules: List<WatchRule>
        get() = WatchRule.listFromJson(prefs.getString(KEY_OPEN_RULES, null))
            ?: WatchRule.defaultOpenRules()
        set(value) = prefs.edit()
            .putString(KEY_OPEN_RULES, WatchRule.listToJson(value)).apply()

    /**
     * How fresh the newest matching image must be to open automatically.
     * Older than this and the app shows a manual "Open an image" button instead.
     */
    var openRecencySeconds: Int
        get() = prefs.getInt(KEY_OPEN_RECENCY, DEFAULT_RECENCY_SECONDS)
        set(value) = prefs.edit()
            .putInt(KEY_OPEN_RECENCY, value.coerceIn(5, 24 * 3600)).apply()

    /** Whether opening the app should auto-load a recent image at all. */
    var openDetectEnabled: Boolean
        get() = prefs.getBoolean(KEY_OPEN_DETECT, true)
        set(value) = prefs.edit().putBoolean(KEY_OPEN_DETECT, value).apply()

    /**
     * Small ring buffer of already-handled file names.
     *
     * A content-trigger job can be woken several times for the same file, since
     * MediaStore updates a row as it is written and indexed. Without this guard the
     * same screenshot would be shrunk repeatedly.
     */
    fun wasProcessed(name: String): Boolean =
        prefs.getStringSet(KEY_SEEN, emptySet())?.contains(name) == true

    fun markProcessed(name: String) {
        val seen = prefs.getStringSet(KEY_SEEN, emptySet()).orEmpty().toMutableList()
        seen.add(name)
        val trimmed = seen.takeLast(SEEN_LIMIT).toSet()
        prefs.edit().putStringSet(KEY_SEEN, trimmed).apply()
    }

    private companion object {
        const val KEY_PRESET = "preset"
        const val KEY_WIDTH = "output_width"
        const val KEY_FORMAT = "format"
        const val KEY_AUTO = "auto_shrink"
        const val KEY_AUTO_NOTIFY = "auto_shrink_notify"
        const val KEY_AUTO_RULES = "auto_shrink_rules"
        const val KEY_OPEN_RULES = "open_rules"
        const val KEY_OPEN_RECENCY = "open_recency_seconds"
        const val KEY_OPEN_DETECT = "open_detect"
        const val KEY_SEEN = "seen_names"
        const val SEEN_LIMIT = 40
        const val DEFAULT_RECENCY_SECONDS = 60
    }
}

/** Human-readable byte size, e.g. "148 KB". */
fun formatBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "${(bytes / 1024.0).toInt()} KB"
    else -> "$bytes B"
}

fun formatBytes(bytes: Int): String = formatBytes(bytes.toLong())

/** "45 seconds" / "3 minutes" / "2 hours", for the recency control and age labels. */
fun formatDuration(seconds: Long): String = when {
    seconds < 60 -> "$seconds sec"
    seconds < 3600 -> "${seconds / 60} min"
    seconds < 86400 -> "${seconds / 3600} h"
    else -> "${seconds / 86400} d"
}
