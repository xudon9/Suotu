package com.xudong.suotu

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore

/**
 * Writes shrunk images into a dedicated gallery album.
 *
 * The album matters for correctness, not just tidiness: the auto-shrink watcher is
 * triggered by new images appearing in MediaStore. If output landed back in
 * Screenshots/, every save would re-trigger the watcher and the app would shrink its
 * own output forever. A separate album plus the filename filter breaks that loop.
 */
object MediaStoreSaver {

    const val ALBUM = "Suotu"
    private val RELATIVE_PATH = "${Environment.DIRECTORY_PICTURES}/$ALBUM"

    /**
     * Save [result] as a new image in Pictures/Suotu.
     *
     * @param baseName source file name, e.g. "Screenshot_20260916_110032.jpg"
     * @return the MediaStore uri of the new item, or null if it could not be created.
     */
    fun save(context: Context, result: ShrinkResult, baseName: String): Uri? {
        val stem = baseName.substringBeforeLast('.')
        val displayName = "${stem}_small.${result.format.extension}"

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, result.format.mimeType)
            put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.Images.Media.WIDTH, result.width)
            put(MediaStore.Images.Media.HEIGHT, result.height)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        ) ?: return null

        return try {
            resolver.openOutputStream(uri)?.use { it.write(result.bytes) }
                ?: throw IllegalStateException("no output stream")

            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri
        } catch (e: Exception) {
            // Do not leave a half-written pending row behind.
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }
}
