package com.xudong.suotu

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shrinks one image and hands it straight to the share sheet, without the main screen.
 *
 * Exists for the on-demand watch mode: tapping "shrink this screenshot?" should not
 * drop the user into an editor when they have already said what they want. The activity
 * is translucent and shows a small progress card, because the encode takes a noticeable
 * moment on a large screenshot and an unexplained pause after a tap reads as a failure.
 *
 * Deliberately an Activity rather than a BroadcastReceiver: since Android 10 an app in
 * the background cannot start an activity, so the share sheet could not be launched from
 * a receiver at all. Being an Activity is also what makes the launch legitimate — the
 * user tapped a notification, so the system allows it.
 *
 * The result is written to the share cache rather than the gallery. The user asked to
 * SEND this shot, not to keep another copy of it; the main screen remains the place that
 * saves.
 */
class QuickShrinkActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uri = intent.parcelableExtra<Uri>(EXTRA_URI)
        if (uri == null) {
            finish()
            return
        }

        setContent {
            MaterialTheme {
                ShrinkingCard()
            }
        }

        lifecycleScope.launch {
            val done = withContext(Dispatchers.Default) { shrinkAndShare(uri) }
            // On failure there is nothing to show and nothing to say; post the usual
            // "ready" notification path is wrong here, so just leave quietly rather than
            // leaving a dead progress card on screen.
            if (!done) finish()
        }
    }

    /** @return true when the share sheet was launched, false if the shrink failed. */
    private fun shrinkAndShare(uri: Uri): Boolean = try {
        val settings = Settings(this)
        val result = ShrinkEngine.shrink(
            context = this,
            uri = uri,
            targetWidth = settings.outputWidth,
            budgetBytes = SizeRange.budgetFor(settings.outputWidth),
            formatPolicy = settings.formatPolicy,
            forcedQuality = settings.manualQuality,
        )
        val file = ShrinkEngine.writeToCache(this, result)
        val shareUri = FileProvider.getUriForFile(
            this, "$packageName.fileprovider", file
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = result.format.mimeType
            putExtra(Intent.EXTRA_STREAM, shareUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runOnUiThread {
            startActivity(
                Intent.createChooser(send, getString(R.string.send_chooser_title))
            )
            finish()
        }
        true
    } catch (e: Exception) {
        false
    }

    /**
     * A small centred card, on a barely-there scrim so it reads as a modal step rather
     * than as a new screen.
     */
    @Composable
    private fun ShrinkingCard() {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color(0x66000000)),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 4.dp,
            ) {
                Column(
                    Modifier.padding(horizontal = 28.dp, vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                    Text(
                        stringResource(R.string.quick_shrinking),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    private inline fun <reified T : android.os.Parcelable> Intent.parcelableExtra(
        key: String,
    ): T? = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(key, T::class.java)
    } else {
        getParcelableExtra(key) as? T
    }

    companion object {
        private const val EXTRA_URI = "com.xudong.suotu.extra.QUICK_URI"

        /** Intent that opens this activity for [uri]. */
        fun intent(context: Context, uri: Uri): Intent =
            Intent(context, QuickShrinkActivity::class.java).apply {
                putExtra(EXTRA_URI, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
    }
}
