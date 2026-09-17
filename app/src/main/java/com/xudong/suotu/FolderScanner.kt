package com.xudong.suotu

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log

/** A folder that currently holds images, for the folder picker. */
data class ImageFolder(
    /** MediaStore RELATIVE_PATH without trailing slash, e.g. "Pictures/Screenshots". */
    val path: String,
    val imageCount: Int,
    /** Newest file name in the folder, shown as a hint of what lives there. */
    val sampleName: String?,
)

/**
 * Discovers image folders and lists file names, backing the folder picker and the
 * regex tester.
 *
 * Typing a path by hand is error-prone — a single wrong segment silently matches
 * nothing — so the UI offers real folders and lets a pattern be tried against real
 * names before it is saved.
 */
object FolderScanner {

    private const val TAG = "SuotuFolderScanner"
    private const val SCAN_LIMIT = 2000

    /** Folders containing images, most populated first. */
    fun listImageFolders(context: Context): List<ImageFolder> {
        val counts = linkedMapOf<String, Int>()
        val samples = mutableMapOf<String, String>()

        queryAll(
            context.contentResolver,
            arrayOf(
                MediaStore.Images.Media.RELATIVE_PATH,
                MediaStore.Images.Media.DISPLAY_NAME,
            ),
            SCAN_LIMIT,
        )?.use { c ->
            val pathCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            while (c.moveToNext()) {
                val path = c.getString(pathCol)?.trim('/')?.takeIf { it.isNotEmpty() }
                    ?: continue
                val name = c.getString(nameCol) ?: continue
                // Do not advertise our own output folder as something to watch.
                if (path.contains(MediaStoreSaver.ALBUM, ignoreCase = true)) continue
                counts[path] = (counts[path] ?: 0) + 1
                samples.putIfAbsent(path, name)
            }
        }

        return counts.entries
            .map { (path, n) -> ImageFolder(path, n, samples[path]) }
            .sortedWith(compareByDescending<ImageFolder> { it.imageCount }.thenBy { it.path })
    }

    /** File names directly inside [path] (newest first), for the regex tester. */
    fun listFileNames(context: Context, path: String, limit: Int = 40): List<String> {
        val target = path.trim('/')
        if (target.isEmpty()) return emptyList()

        val out = mutableListOf<String>()
        queryAll(
            context.contentResolver,
            arrayOf(
                MediaStore.Images.Media.RELATIVE_PATH,
                MediaStore.Images.Media.DISPLAY_NAME,
            ),
            SCAN_LIMIT,
        )?.use { c ->
            val pathCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            while (c.moveToNext() && out.size < limit) {
                val p = c.getString(pathCol)?.trim('/') ?: continue
                val matchesFolder = p.equals(target, true) ||
                    p.startsWith("$target/", true)
                if (!matchesFolder) continue
                out += c.getString(nameCol) ?: continue
            }
        }
        return out
    }

    /**
     * Convert a Storage Access Framework tree uri into a MediaStore-style relative path.
     *
     * SAF hands back an opaque document id like "primary:Pictures/Screenshots"; the
     * watcher works in MediaStore relative paths, so only primary shared storage can be
     * translated. Anything else (SD card, cloud provider) returns null and the UI says
     * so rather than saving a rule that could never match.
     */
    fun treeUriToRelativePath(uri: Uri): String? {
        val docId = runCatching {
            android.provider.DocumentsContract.getTreeDocumentId(uri)
        }.getOrNull() ?: return null

        val parts = docId.split(':', limit = 2)
        if (parts.size != 2) return null
        if (!parts[0].equals("primary", ignoreCase = true)) return null
        return parts[1].trim('/').takeIf { it.isNotEmpty() }
    }

    private fun queryAll(
        resolver: ContentResolver,
        projection: Array<String>,
        limit: Int,
    ) = runCatching {
        val sort = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val extras = Bundle().apply {
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
                projection, null, null, "$sort LIMIT $limit"
            )
        }
    }.onFailure { Log.e(TAG, "folder scan failed", it) }.getOrNull()
}
