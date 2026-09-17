package com.xudong.suotu

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/** Posts the silent "a small copy is ready" notification. Tapping it shares. */
object ShrinkNotifier {

    private const val CHANNEL_ID = "auto_shrink"
    private const val NOTIFICATION_ID = 4712

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

    fun notifyReady(context: Context, uri: Uri, result: ShrinkResult) {
        ensureChannel(context)

        // Tapping the notification goes straight to the share sheet — that is the whole
        // point of the shrunk copy, so it should not cost an extra hop through a viewer.
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
            .build()

        runCatching {
            NotificationManagerCompat.from(context)
                .notify(NOTIFICATION_ID, notification)
        }
    }
}
