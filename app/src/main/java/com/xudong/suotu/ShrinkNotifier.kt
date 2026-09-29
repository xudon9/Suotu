package com.xudong.suotu

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * The watcher's notifications.
 *
 * Two shapes, matching the two watch modes:
 *
 *  - [notifyReady]     eager mode: the small copy already exists, tap to send it.
 *  - [notifyAvailable] on-demand mode: nothing has been written yet; tap to open the
 *                      screenshot in the app, where it is shrunk and can be sent.
 *
 * Both carry a thumbnail. Without one the notification says a picture is ready but not
 * WHICH picture, which is useless when several screenshots arrive close together.
 */
object ShrinkNotifier {

    private const val CHANNEL_ID = "auto_shrink"

    /** Eager mode: "a small copy is ready". */
    private const val NOTIFICATION_ID = 4712

    /**
     * On-demand mode: "this can be shrunk".
     *
     * A single id, so a newer offer REPLACES an older one rather than stacking. In this
     * mode every screenshot produces a notification, and letting them pile up would turn
     * the shade into a list of every screenshot ever taken. The offer is about the shot
     * just taken, and older ones going stale is the right trade.
     */
    private const val OFFER_ID = 4713

    /**
     * Longest side of the expanded thumbnail, in pixels.
     *
     * Notification payloads cross a Binder transaction with a hard size limit, and an
     * oversized bitmap gets the notification dropped — silently, which would look like
     * the feature simply not working. A 9:20 screenshot at this size is about 66k
     * pixels, well inside the budget even before the system scales it.
     */
    private const val BIG_PICTURE_PX = 384

    /** Longest side of the collapsed thumbnail. */
    private const val LARGE_ICON_PX = 128

    private fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notif_channel),
            // LOW: appears in the shade without sound or heads-up interruption.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        context.getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(channel)
    }

    /**
     * Decode [uri] down to at most [maxPx] on its longest side.
     *
     * Uses inSampleSize so the full-resolution bitmap is never allocated — a 1260x2800
     * screenshot decoded whole would be 14 MB, in a background job, to produce a
     * thumbnail.
     */
    private fun thumbnail(context: Context, uri: Uri, maxPx: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        if (longest <= 0) return@runCatching null

        var sample = 1
        while (longest / (sample * 2) >= maxPx) sample *= 2

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            // 565 halves the memory. These are opaque screenshots shown at thumbnail
            // size; the missing alpha and colour depth are not visible here.
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
    }.getOrNull()

    private fun NotificationCompat.Builder.withThumbnail(
        context: Context,
        uri: Uri,
    ): NotificationCompat.Builder {
        val big = thumbnail(context, uri, BIG_PICTURE_PX)
        val small = thumbnail(context, uri, LARGE_ICON_PX)
        if (small != null) setLargeIcon(small)
        if (big != null) {
            setStyle(
                NotificationCompat.BigPictureStyle()
                    .bigPicture(big)
                    // Conventional: the collapsed thumbnail disappears when expanded,
                    // so the picture is not shown twice.
                    .bigLargeIcon(null as Bitmap?)
            )
        }
        return this
    }

    /**
     * Eager mode: a smaller copy has been written and is ready to send.
     *
     * The thumbnail is of the RESULT rather than the source, because that is what
     * tapping will send — showing the original would misrepresent it.
     */
    fun notifyReady(context: Context, uri: Uri, result: ShrinkResult) {
        ensureChannel(context)

        // Tapping goes straight to the share sheet — that is the whole point of the
        // shrunk copy, so it should not cost an extra hop through a viewer.
        val share = Intent(Intent.ACTION_SEND).apply {
            type = result.format.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(
            share, context.getString(R.string.send_chooser_title)
        ).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val sharePending = PendingIntent.getActivity(
            context, uri.hashCode(), chooser,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val text = if (result.sourceBytes > 0) {
            context.getString(
                R.string.notif_text_saved,
                formatBytes(result.sourceBytes),
                formatBytes(result.outputBytes),
                ((1f - result.ratio) * 100).toInt(),
            )
        } else {
            context.getString(
                R.string.notif_text,
                formatBytes(result.sourceBytes),
                formatBytes(result.outputBytes),
            )
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_title))
            .setContentText(text)
            .setSubText(context.getString(R.string.notif_tap_hint))
            .setContentIntent(sharePending)
            .setAutoCancel(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .withThumbnail(context, uri)
            .build()

        runCatching {
            NotificationManagerCompat.from(context)
                .notify(NOTIFICATION_ID, notification)
        }
    }

    /**
     * On-demand mode: a screenshot was detected, but nothing has been shrunk or written.
     *
     * Doing the work on tap rather than on detection is the point of this mode: no files
     * appear in the gallery for screenshots you were never going to send.
     *
     * Both ways of acting are offered as ACTION BUTTONS, so the choice is made per
     * screenshot at the moment of tapping rather than only as a preference set in
     * advance. The setting still decides what tapping the notification BODY does, which
     * is what a quick tap should do — but a button is always available to override it,
     * because whether a given shot needs cropping is not knowable ahead of time.
     */
    fun notifyAvailable(context: Context, uri: Uri, sourceBytes: Long) {
        ensureChannel(context)

        // Where a tap lands depends on the user's choice, and the two are genuinely
        // different intentions rather than a preference about styling:
        //
        //   direct - shrink and share. The tap WAS the decision.
        //   edit   - open the editor first, to crop or annotate before sending.
        //
        // The edit path reuses the ordinary ACTION_SEND entry point, so it cannot drift
        // from a normal share into the app.
        // The two ways of acting, built once and used for both the body tap and the
        // buttons.
        //
        // The edit path reuses the ordinary ACTION_SEND entry point, so it cannot drift
        // from a normal share into the app.
        val directIntent = QuickShrinkActivity.intent(context, uri)
        val editIntent = Intent(context, ShrinkActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "image/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }

        // Distinct request codes per screenshot. PendingIntent equality ignores extras,
        // so the three intents for one image must not share a code — with
        // FLAG_UPDATE_CURRENT one would silently overwrite another and a button would
        // launch the wrong thing.
        val base = uri.hashCode()
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

        val openPending = PendingIntent.getActivity(
            context, base, (if (Settings(context).autoShrinkDirectShare) directIntent
            else editIntent).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }, flags,
        )
        val directPending = PendingIntent.getActivity(
            context, base + 1, directIntent.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
            flags,
        )
        val editPending = PendingIntent.getActivity(
            context, base + 2, editIntent.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
            flags,
        )

        val text = if (sourceBytes > 0) {
            context.getString(R.string.notif_offer_text, formatBytes(sourceBytes))
        } else {
            context.getString(R.string.notif_offer_text_plain)
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.notif_offer_title))
            .setContentText(text)
            .setSubText(context.getString(R.string.notif_offer_hint))
            .setContentIntent(openPending)
            // Both choices as buttons. Labels are kept short on purpose: action buttons
            // are single-line and get truncated on a narrow shade.
            .addAction(
                R.drawable.ic_notification,
                context.getString(R.string.notif_action_share),
                directPending,
            )
            .addAction(
                R.drawable.ic_notification,
                context.getString(R.string.notif_action_edit),
                editPending,
            )
            .setAutoCancel(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .withThumbnail(context, uri)
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(OFFER_ID, notification)
        }
    }
}
