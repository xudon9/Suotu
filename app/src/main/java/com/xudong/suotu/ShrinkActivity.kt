package com.xudong.suotu

import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Main screen. Handles three entry paths:
 *
 *  - a share intent,
 *  - opened from the launcher with a *recent* matching image, loaded automatically per
 *    [Settings.openRules] / [Settings.openRecencySeconds],
 *  - opened with nothing recent, which offers a manual picker.
 */
class ShrinkActivity : AppCompatActivity() {

    private sealed interface UiState {
        data class Empty(val reason: String) : UiState
        data object Working : UiState
        data class Ready(val result: ShrinkResult) : UiState
        data class Failed(val message: String) : UiState
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val settings = Settings(this)
        val shared = extractImageUri(intent)

        // Self-heal: vivo's battery manager and force-stop can silently drop jobs.
        if (settings.autoShrinkEnabled && !ScreenshotWatcherJob.isScheduled(this)) {
            ScreenshotWatcherJob.schedule(this)
        }

        setContent {
            SuotuTheme {
                val context = LocalContext.current
                var width by remember { mutableIntStateOf(settings.outputWidth) }
                var sliderPos by remember { mutableFloatStateOf(width.toFloat()) }
                var policy by remember { mutableStateOf(settings.formatPolicy) }
                var source by remember { mutableStateOf(shared) }
                var manualPick by remember { mutableStateOf(false) }
                var crop by remember { mutableStateOf(CropRect.FULL) }
                var cropping by remember { mutableStateOf(false) }
                var fullscreen by remember { mutableStateOf(false) }
                var sourceImage by remember { mutableStateOf<ImageBitmap?>(null) }
                var state by remember {
                    mutableStateOf<UiState>(
                        UiState.Empty(getString(R.string.looking_for_recent))
                    )
                }

                val picker = rememberLauncherForActivityResult(
                    ActivityResultContracts.PickVisualMedia()
                ) { picked ->
                    if (picked != null) {
                        manualPick = true
                        crop = CropRect.FULL
                        source = picked
                    }
                }

                // Re-check on every resume, not just the first composition. Returning
                // from the background does not re-run onCreate, so keying on Unit here
                // would only ever fire on a cold start.
                val lifecycleOwner = LocalLifecycleOwner.current
                var resumeTick by remember { mutableIntStateOf(0) }
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) resumeTick++
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                LaunchedEffect(resumeTick) {
                    if (shared != null) return@LaunchedEffect
                    if (manualPick) return@LaunchedEffect
                    if (!settings.openDetectEnabled) {
                        state = UiState.Empty(getString(R.string.pick_an_image))
                        return@LaunchedEffect
                    }
                    if (!hasMediaReadPermission(context)) {
                        state = UiState.Empty(getString(R.string.need_photo_access))
                        return@LaunchedEffect
                    }

                    val window = settings.openRecencySeconds.toLong()
                    val newest = withContext(Dispatchers.IO) {
                        MediaQuery.newestMatching(
                            context, settings.openRules, maxAgeSeconds = window
                        )
                    }
                    when {
                        newest != null && newest.uri != source -> {
                            crop = CropRect.FULL
                            source = newest.uri
                        }
                        newest != null -> Unit
                        state !is UiState.Ready -> state = UiState.Empty(
                            getString(R.string.no_match_recent, formatDuration(window))
                        )
                    }
                }

                // A downscaled copy of the source, used only for the crop canvas.
                LaunchedEffect(source) {
                    val uri = source
                    sourceImage = if (uri == null) null else withContext(Dispatchers.IO) {
                        runCatching { ShrinkEngine.loadPreview(context, uri) }
                            .getOrNull()?.asImageBitmap()
                    }
                }

                LaunchedEffect(source, width, policy, crop) {
                    val uri = source ?: return@LaunchedEffect
                    // Debounce: the slider fires continuously while dragging, and each
                    // re-encode runs a multi-step quality search.
                    delay(180)
                    state = UiState.Working
                    state = try {
                        val result = withContext(Dispatchers.Default) {
                            ShrinkEngine.shrink(
                                context = context,
                                uri = uri,
                                targetWidth = width,
                                budgetBytes = SizeRange.budgetFor(width),
                                formatPolicy = policy,
                                crop = crop.takeIf { !it.isFullFrame },
                            )
                        }
                        UiState.Ready(result)
                    } catch (e: SecurityException) {
                        UiState.Failed(getString(R.string.err_lost_access))
                    } catch (e: OutOfMemoryError) {
                        UiState.Failed(getString(R.string.err_too_large))
                    } catch (e: Exception) {
                        UiState.Failed(e.message ?: getString(R.string.err_generic))
                    }
                }

                val img = sourceImage

                // Crop mode gets its own NON-SCROLLING full-height layout.
                //
                // It cannot live inside the scrolling column below: a verticalScroll
                // measures its children with infinite height, so weight(1f) has nothing
                // to divide and the crop canvas grows to the image's natural height,
                // pushing the action buttons off-screen. Disabling the scroll does not
                // help, because the constraints are still unbounded.
                if (cropping && img != null) {
                    Surface(Modifier.fillMaxSize()) {
                        Column(
                            Modifier
                                .fillMaxSize()
                                .safeDrawingPadding()
                                .padding(20.dp),
                        ) {
                            Text(
                                stringResource(R.string.crop_title),
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                            )
                            Spacer(Modifier.height(8.dp))
                            CropEditor(
                                image = img,
                                crop = crop,
                                onCropChange = { crop = it },
                                onApply = { cropping = false },
                                onCancel = {
                                    crop = CropRect.FULL
                                    cropping = false
                                },
                            )
                        }
                    }
                } else Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .safeDrawingPadding()
                            .padding(20.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(R.string.app_name),
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = {
                                startActivity(Intent(context, HelpActivity::class.java))
                            }) { Text(stringResource(R.string.help)) }
                            TextButton(onClick = {
                                startActivity(
                                    Intent(context, SettingsActivity::class.java)
                                )
                            }) { Text(stringResource(R.string.settings)) }
                        }

                        Spacer(Modifier.height(8.dp))

                        run {
                            WidthSlider(
                                width = width,
                                sliderPos = sliderPos,
                                onSlide = {
                                    sliderPos = it
                                    width = (it / SizeRange.STEP).toInt() *
                                        SizeRange.STEP
                                },
                                onQuickPick = {
                                    width = it
                                    sliderPos = it.toFloat()
                                    settings.outputWidth = it
                                },
                                onSettle = { settings.outputWidth = width },
                            )

                            Spacer(Modifier.height(10.dp))
                            FormatRow(selected = policy) {
                                policy = it
                                settings.formatPolicy = it
                            }

                            Spacer(Modifier.height(16.dp))

                            when (val s = state) {
                                is UiState.Empty -> EmptyState(
                                    message = s.reason,
                                    onPick = { picker.launchImage() },
                                )

                                is UiState.Working -> Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(220.dp),
                                    contentAlignment = Alignment.Center,
                                ) { CircularProgressIndicator() }

                                is UiState.Failed -> Column {
                                    Text(
                                        s.message,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                    Spacer(Modifier.height(12.dp))
                                    OutlinedButton(onClick = { picker.launchImage() }) {
                                        Text(stringResource(R.string.open_an_image))
                                    }
                                }

                                is UiState.Ready -> {
                                    ResultCard(
                                        result = s.result,
                                        onTapPreview = { fullscreen = true },
                                    )
                                    Spacer(Modifier.height(12.dp))
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement =
                                            Arrangement.spacedBy(8.dp),
                                    ) {
                                        OutlinedButton(
                                            onClick = { picker.launchImage() },
                                            modifier = Modifier.weight(1f),
                                        ) { Text(stringResource(R.string.change)) }

                                        OutlinedButton(
                                            onClick = { cropping = true },
                                            enabled = sourceImage != null,
                                            modifier = Modifier.weight(1f),
                                        ) { Text(stringResource(R.string.crop)) }

                                        Button(
                                            onClick = { sendResult(s.result) },
                                            modifier = Modifier.weight(1.6f),
                                        ) {
                                            Text(
                                                stringResource(
                                                    R.string.send_size,
                                                    formatBytes(s.result.outputBytes),
                                                )
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (fullscreen) {
                    (state as? UiState.Ready)?.let { ready ->
                        val bmp = remember(ready.result) {
                            BitmapFactory.decodeByteArray(
                                ready.result.bytes, 0, ready.result.bytes.size
                            )?.asImageBitmap()
                        }
                        if (bmp != null) {
                            FullscreenPreview(
                                image = bmp,
                                caption = "${ready.result.width}×" +
                                    "${ready.result.height} · " +
                                    formatBytes(ready.result.outputBytes),
                                onDismiss = { fullscreen = false },
                            )
                        }
                    }
                }
            }
        }
    }

    /** Continuous width control, with the measured presets as quick jumps. */
    @Composable
    private fun WidthSlider(
        width: Int,
        sliderPos: Float,
        onSlide: (Float) -> Unit,
        onQuickPick: (Int) -> Unit,
        onSettle: () -> Unit,
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.width_label),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(R.string.width_value, width),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Slider(
                value = sliderPos,
                onValueChange = onSlide,
                onValueChangeFinished = onSettle,
                valueRange = SizeRange.MIN_WIDTH.toFloat()..SizeRange.MAX_WIDTH.toFloat(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SizeRange.quickPicks.forEach { preset ->
                    FilterChip(
                        selected = width == preset.targetWidth,
                        onClick = { onQuickPick(preset.targetWidth) },
                        label = {
                            Text(
                                "${stringResource(preset.labelRes)} " +
                                    "${preset.targetWidth}"
                            )
                        },
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                stringResource(R.string.quality_auto_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    @Composable
    private fun EmptyState(message: String, onPick: () -> Unit) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(message, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                Button(onClick = onPick) {
                    Text(stringResource(R.string.open_an_image))
                }
                Spacer(Modifier.height(16.dp))
                Text(
                    stringResource(R.string.usage_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    @Composable
    private fun FormatRow(selected: FormatPolicy, onPick: (FormatPolicy) -> Unit) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FormatPolicy.entries.forEach { f ->
                FilterChip(
                    selected = f == selected,
                    onClick = { onPick(f) },
                    label = { Text(stringResource(f.labelRes)) },
                )
            }
        }
    }

    @Composable
    private fun ResultCard(result: ShrinkResult, onTapPreview: () -> Unit) {
        Card(Modifier.fillMaxWidth()) {
            Column {
                val bitmap = remember(result) {
                    BitmapFactory.decodeByteArray(result.bytes, 0, result.bytes.size)
                }
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(240.dp)
                            .background(MaterialTheme.colorScheme.previewBackdrop)
                            .clickable { onTapPreview() },
                        contentScale = ContentScale.Fit,
                    )
                }

                Column(Modifier.padding(16.dp)) {
                    Text(
                        stringResource(R.string.preview_fullscreen),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(Modifier.fillMaxWidth()) {
                        StatColumn(
                            stringResource(R.string.before),
                            formatBytes(result.sourceBytes),
                            "${result.sourceWidth}×${result.sourceHeight}",
                            Modifier.weight(1f),
                        )
                        StatColumn(
                            stringResource(R.string.after),
                            formatBytes(result.outputBytes),
                            "${result.width}×${result.height}",
                            Modifier.weight(1f),
                        )
                        StatColumn(
                            stringResource(R.string.saved),
                            if (result.sourceBytes > 0)
                                "${((1f - result.ratio) * 100).toInt()}%" else "—",
                            "${result.format.label} q${result.quality}",
                            Modifier.weight(1f),
                        )
                    }

                    if (result.budgetMissed) {
                        Spacer(Modifier.height(10.dp))
                        Text(
                            stringResource(R.string.budget_missed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun StatColumn(
        label: String,
        value: String,
        sub: String,
        modifier: Modifier = Modifier,
    ) {
        Column(modifier) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                sub,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    private fun sendResult(result: ShrinkResult) {
        val file = ShrinkEngine.writeToCache(this, result)
        val uri: Uri = FileProvider.getUriForFile(
            this, "$packageName.fileprovider", file
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = result.format.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(
            Intent.createChooser(send, getString(R.string.send_chooser_title))
        )
        finish()
    }

    private fun extractImageUri(intent: Intent?): Uri? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_SEND -> intent.parcelableExtra(Intent.EXTRA_STREAM)
            Intent.ACTION_SEND_MULTIPLE ->
                intent.parcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.firstOrNull()
            else -> null
        }
    }

    @Suppress("DEPRECATION")
    private inline fun <reified T : android.os.Parcelable> Intent.parcelableExtra(
        key: String,
    ): T? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableExtra(key, T::class.java)
    } else {
        getParcelableExtra(key) as? T
    }

    @Suppress("DEPRECATION")
    private inline fun <reified T : android.os.Parcelable> Intent.parcelableArrayListExtra(
        key: String,
    ): ArrayList<T>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableArrayListExtra(key, T::class.java)
    } else {
        getParcelableArrayListExtra(key)
    }
}

/** Shorthand for the image-only picker request. */
private fun androidx.activity.result.ActivityResultLauncher<PickVisualMediaRequest>
    .launchImage() = launch(
    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
)
