package com.xudong.suotu

import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject

/**
 * One "watch this folder for files named like this" rule.
 *
 * @param path MediaStore RELATIVE_PATH prefix, e.g. "Pictures/Screenshots".
 *             Matching is prefix-based, so "DCIM" also covers "DCIM/Camera".
 * @param pattern regex matched against the file NAME (not the path).
 * @param enabled lets a rule be switched off without deleting it.
 */
data class WatchRule(
    val path: String,
    val pattern: String,
    val enabled: Boolean = true,
) {
    val normalizedPath: String get() = path.trim('/')

    /**
     * Compiled form of [pattern], or null if the user typed an invalid regex.
     * Compiling per rule keeps one bad pattern from breaking the whole list.
     */
    val regex: Regex?
        get() = runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrNull()

    val isValid: Boolean get() = path.isNotBlank() && regex != null

    fun matches(relativePath: String?, displayName: String): Boolean {
        if (!enabled) return false
        val re = regex ?: return false
        if (!re.matches(displayName)) return false
        // A blank path from MediaStore means we cannot confirm the folder — be strict.
        val actual = relativePath?.trim('/')?.takeIf { it.isNotEmpty() } ?: return false
        return actual.equals(normalizedPath, ignoreCase = true) ||
            actual.startsWith("$normalizedPath/", ignoreCase = true)
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put(KEY_PATH, path)
        put(KEY_PATTERN, pattern)
        put(KEY_ENABLED, enabled)
    }

    companion object {
        private const val KEY_PATH = "path"
        private const val KEY_PATTERN = "pattern"
        private const val KEY_ENABLED = "enabled"

        /** The system screenshot naming, e.g. Screenshot_20260916_110032.jpg */
        const val SCREENSHOT_REGEX = """^Screenshot_\d{8}_\d{6}\.(jpg|jpeg|png|webp)$"""

        /** Anything that looks like an image file. */
        const val ANY_IMAGE_REGEX = """^.*\.(jpg|jpeg|png|webp)$"""

        val SCREENSHOTS_DIR: String = "${Environment.DIRECTORY_PICTURES}/Screenshots"
        val CAMERA_DIR: String = "${Environment.DIRECTORY_DCIM}/Camera"

        /**
         * Default for the SILENT background watcher: screenshots only.
         *
         * Deliberately narrow. Auto-shrinking every camera photo would fill the gallery
         * with unwanted copies, so the watch list stays tighter than the open list.
         */
        fun defaultAutoShrinkRules() = listOf(
            WatchRule(SCREENSHOTS_DIR, SCREENSHOT_REGEX),
        )

        /**
         * Default for OPEN-detection: screenshots and camera shots.
         *
         * Broader is safe here because opening the app is a deliberate act and nothing
         * is written automatically — the app merely offers the newest image.
         */
        fun defaultOpenRules() = listOf(
            WatchRule(SCREENSHOTS_DIR, SCREENSHOT_REGEX),
            WatchRule(CAMERA_DIR, ANY_IMAGE_REGEX),
        )

        fun fromJson(o: JSONObject) = WatchRule(
            path = o.optString(KEY_PATH),
            pattern = o.optString(KEY_PATTERN),
            enabled = o.optBoolean(KEY_ENABLED, true),
        )

        fun listToJson(rules: List<WatchRule>): String =
            JSONArray().apply { rules.forEach { put(it.toJson()) } }.toString()

        fun listFromJson(raw: String?): List<WatchRule>? {
            if (raw.isNullOrBlank()) return null
            return runCatching {
                val arr = JSONArray(raw)
                (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
            }.getOrNull()
        }
    }
}
