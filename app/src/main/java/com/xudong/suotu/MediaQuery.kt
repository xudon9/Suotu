package com.xudong.suotu

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log

/** An image row from MediaStore, reduced to what the rules need. */
data class MediaItem(
    val uri: Uri,
    val displayName: String,
    val relativePath: String?,
    /** Seconds since epoch, as MediaStore stores DATE_ADDED. */
    val addedAtSeconds: Long,
) {
    val ageSeconds: Long
        get() = (System.currentTimeMillis() / 1000) - addedAtSeconds
}

/**
 * Finds recent images and tests them against a set of [WatchRule]s.
 *
 * Shared by the background watcher and the open-detect path so both agree on what
 * "the newest matching image" means — they simply pass different rule lists.
 */
object MediaQuery {

    private const val TAG = "SuotuMediaQuery"

    /**
     * Newest image matching any enabled rule, or null.
     *
     * @param maxAgeSeconds ignore anything older (0 or negative = no age limit).
     * @param scanLimit how many recent rows to examine. Rules are applied in Kotlin
     *        rather than SQL because a user-supplied regex cannot be pushed into a
     *        MediaStore selection safely.
     */
    fun newestMatching(
        context: Context,
        rules: List<WatchRule>,
        maxAgeSeconds: Long = 0,
        scanLimit: Int = 40,
    ): MediaItem? = recentMatching(context, rules, maxAgeSeconds, scanLimit).firstOrNull()

    /** All recent images matching any enabled rule, newest first. */
    fun recentMatching(
        context: Context,
        rules: List<WatchRule>,
        maxAgeSeconds: Long = 0,
        scanLimit: Int = 40,
    ): List<MediaItem> {
        val active = rules.filter { it.enabled && it.isValid }
        if (active.isEmpty()) {
            Log.w(TAG, "no valid rules to match against")
            return emptyList()
        }

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.DATE_ADDED,
        )

        var selection: String? = null
        var args: Array<String>? = null
        if (maxAgeSeconds > 0) {
            val cutoff = (System.currentTimeMillis() / 1000) - maxAgeSeconds
            selection = "${MediaStore.Images.Media.DATE_ADDED} >= ?"
            args = arrayOf(cutoff.toString())
        }

        val out = mutableListOf<MediaItem>()
        try {
            queryImages(context.contentResolver, projection, selection, args, scanLimit)
                ?.use { c ->
                    val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val nameCol =
                        c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                    val pathCol =
                        c.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
                    val dateCol =
                        c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)

                    while (c.moveToNext()) {
                        val name = c.getString(nameCol) ?: continue
                        val path = c.getString(pathCol)

                        // Never offer or re-shrink our own output.
                        if (isOwnOutput(path, name)) continue
                        if (active.none { it.matches(path, name) }) continue

                        out += MediaItem(
                            uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                                .buildUpon()
                                .appendPath(c.getLong(idCol).toString())
                                .build(),
                            displayName = name,
                            relativePath = path,
                            addedAtSeconds = c.getLong(dateCol),
                        )
                    }
                }
        } catch (e: Exception) {
            // Log loudly: swallowing this once hid a real query bug for a whole round
            // of testing, presenting as "no recent image" with no other symptom.
            Log.e(TAG, "media query failed", e)
        }
        return out
    }

    /**
     * Run the query with a row limit.
     *
     * The limit must NOT be appended to the sort-order string ("date_added DESC LIMIT
     * 40"). That is a legacy SQLite trick which Android 11+ rejects, and the resulting
     * exception surfaces as an empty result — the app looks like it simply found
     * nothing. From API 30 the limit belongs in a query [Bundle]; below that the old
     * form is still the only option.
     */
    private fun queryImages(
        resolver: ContentResolver,
        projection: Array<String>,
        selection: String?,
        args: Array<String>?,
        limit: Int,
    ): Cursor? {
        val sort = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val extras = Bundle().apply {
                selection?.let {
                    putString(ContentResolver.QUERY_ARG_SQL_SELECTION, it)
                }
                args?.let {
                    putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, it)
                }
                putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, sort)
                putInt(ContentResolver.QUERY_ARG_LIMIT, limit)
            }
            resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, extras, null
            )
        } else {
            @Suppress("DEPRECATION")
            resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection, selection, args, "$sort LIMIT $limit"
            )
        }
    }

    /**
     * Guard against the app consuming its own output.
     *
     * Without this, a rule as ordinary as "Pictures + any image" would make the watcher
     * shrink its own result, then shrink that, forever. Checked by album AND by the
     * name suffix so a rule pointed straight at the output folder is still safe.
     */
    fun isOwnOutput(relativePath: String?, displayName: String): Boolean {
        val inAlbum = relativePath?.trim('/')
            ?.contains(MediaStoreSaver.ALBUM, ignoreCase = true) == true
        val named = displayName.contains(SMALL_SUFFIX, ignoreCase = true)
        return inAlbum || named
    }

    const val SMALL_SUFFIX = "_small"
}
