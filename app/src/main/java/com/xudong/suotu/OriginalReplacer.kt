package com.xudong.suotu

import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log

/** Outcome of trying to overwrite an original image in place. */
sealed interface ReplaceResult {
    data object Success : ReplaceResult

    /**
     * Android wants the user to confirm before the app may modify a file it does not
     * own. The caller must launch [intentSender] and retry afterwards.
     */
    data class NeedsPermission(val intentSender: IntentSender) : ReplaceResult

    data class Failed(val reason: String) : ReplaceResult
}

/**
 * Overwrites the original gallery image with the shrunk bytes.
 *
 * This is destructive and unrecoverable, so it is deliberately a separate, explicit
 * action rather than a side effect of sharing.
 *
 * Two platform details shape the implementation:
 *
 *  1. On Android 10+ an app may not silently modify media it did not create. The system
 *     throws [RecoverableSecurityException] carrying a user-consent dialog, which must
 *     be surfaced and the write retried. Without handling it, replacing anything the
 *     user captured with their camera or system screenshot tool would simply fail.
 *  2. The shrunk bytes may be WebP while the original is a .jpg. Writing them under the
 *     old name would leave a file whose extension lies about its contents, which some
 *     galleries and chat apps handle badly, so the row's name and MIME type are updated
 *     together with the bytes.
 */
object OriginalReplacer {

    private const val TAG = "SuotuReplacer"

    /**
     * @param uri the ORIGINAL image's MediaStore uri.
     * @param result the shrunk bytes to write in its place.
     */
    fun replace(context: Context, uri: Uri, result: ShrinkResult): ReplaceResult {
        // Only MediaStore rows can be rewritten in place. A transient share uri from
        // another app (or a picker uri) is read-only by design.
        if (!isMediaStoreUri(uri)) {
            return ReplaceResult.Failed("not a gallery item")
        }

        val resolver = context.contentResolver
        return try {
            // Keep the extension honest when the format changed.
            val currentName = queryName(context, uri)
            val newName = currentName?.let { renameForFormat(it, result.format) }

            // "w" truncates, so the old, larger content cannot survive as a tail.
            resolver.openOutputStream(uri, "w")?.use { out ->
                out.write(result.bytes)
            } ?: return ReplaceResult.Failed("could not open the file for writing")

            val values = ContentValues().apply {
                put(MediaStore.Images.Media.MIME_TYPE, result.format.mimeType)
                put(MediaStore.Images.Media.WIDTH, result.width)
                put(MediaStore.Images.Media.HEIGHT, result.height)
                put(MediaStore.Images.Media.SIZE, result.bytes.size)
                if (newName != null && newName != currentName) {
                    put(MediaStore.Images.Media.DISPLAY_NAME, newName)
                }
            }
            resolver.update(uri, values, null, null)

            ReplaceResult.Success
        } catch (e: SecurityException) {
            // Android 10+: the user must approve editing media the app does not own.
            val recoverable = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                e as? RecoverableSecurityException
            } else {
                null
            }
            if (recoverable != null) {
                ReplaceResult.NeedsPermission(
                    recoverable.userAction.actionIntent.intentSender
                )
            } else {
                Log.w(TAG, "replace denied", e)
                ReplaceResult.Failed(e.message ?: "permission denied")
            }
        } catch (e: Exception) {
            Log.w(TAG, "replace failed", e)
            ReplaceResult.Failed(e.message ?: "unknown error")
        }
    }

    /** True if [uri] is a MediaStore item this app can attempt to rewrite. */
    fun isMediaStoreUri(uri: Uri): Boolean =
        uri.scheme == "content" && uri.authority == MediaStore.AUTHORITY

    fun queryName(context: Context, uri: Uri): String? =
        runCatching {
            context.contentResolver.query(
                uri, arrayOf(MediaStore.Images.Media.DISPLAY_NAME), null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull()

    /**
     * The file name this image should carry once written as [format].
     * Returns the original name unchanged when the extension already matches.
     */
    fun renameForFormat(name: String, format: EncodedFormat): String {
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot + 1).lowercase() else ""

        val matches = when (format) {
            EncodedFormat.JPEG -> ext == "jpg" || ext == "jpeg"
            EncodedFormat.WEBP -> ext == "webp"
            EncodedFormat.PNG -> ext == "png"
        }
        return if (matches) name else "$stem.${format.extension}"
    }

    /** Ask the system for consent, using the sender from [ReplaceResult.NeedsPermission]. */
    fun requestConsent(activity: Activity, sender: IntentSender, requestCode: Int) {
        activity.startIntentSenderForResult(sender, requestCode, null, 0, 0, 0)
    }
}
