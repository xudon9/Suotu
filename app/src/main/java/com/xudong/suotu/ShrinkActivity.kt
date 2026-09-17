package com.xudong.suotu

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
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
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Main screen. Handles three entry paths:
 *
 *  - a share intent,
 *  - opened from the launcher with a *recent* matching image, loaded automatically per
 *    [Settings.openRules] / [Settings.openRecencySeconds],
 *  - opened with nothing recent, which offers a manual picker.
 *
 * Layout priorities, learned from testing on a real phone:
 *
 *  - The PREVIEW gets the space. It is what the user is judging; the controls are not.
 *  - Width / quality / format live in a collapsed [OptionsSection]. They are remembered
 *    across launches and rarely changed, and expanded they cost about a third of the
 *    screen.
 *  - Actions are FIXED-SIZE icons, never weighted text. Five Chinese labels across a
 *    1260px screen were each clipped to a single glyph (标注 vs 标准 became
 *    indistinguishable) and the row still overflowed the bottom edge.
 */
class ShrinkActivity : AppCompatActivity() {

    /** Write awaiting the system's media-edit consent dialog. */
    private var pendingReplace: Pair<Uri, ShrinkResult>? = null

    private companion object {
        const val REQUEST_REPLACE_CONSENT = 9101
    }

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
                var policy by remember { mutableStateOf(settings.formatPolicy) }
                var manualQuality by remember { mutableStateOf(settings.manualQuality) }
                // Expanded when the app is opened cold with nothing loaded — there is
                // room, and the controls are what the screen is for at that point. As
                // soon as an image arrives the preview takes priority, so it folds.
                var optionsExpanded by remember { mutableStateOf(shared == null) }
                var foldedForImage by remember { mutableStateOf(false) }
                var source by remember { mutableStateOf(shared) }
                var manualPick by remember { mutableStateOf(false) }
                var crop by remember { mutableStateOf(CropRect.FULL) }
                var cropping by remember { mutableStateOf(false) }
                var fullscreen by remember { mutableStateOf(false) }
                var confirmReplace by remember { mutableStateOf(false) }
                var saveMenuOpen by remember { mutableStateOf(false) }
                var annotating by remember { mutableStateOf(false) }
                var annotations by remember { mutableStateOf(AnnotationState()) }
                var toast by remember { mutableStateOf<String?>(null) }
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
                        annotations = AnnotationState()
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
                            annotations = AnnotationState()
                            source = newest.uri
                        }
                        newest != null -> Unit
                        state !is UiState.Ready -> state = UiState.Empty(
                            getString(R.string.no_match_recent, formatDuration(window))
                        )
                    }
                }

                // Fold the options once an image is actually loaded, but only the FIRST
                // time: re-folding on every re-encode would fight the user every time
                // they moved a slider.
                LaunchedEffect(source) {
                    if (source != null && !foldedForImage) {
                        foldedForImage = true
                        optionsExpanded = false
                    }
                }

                // A bounded-resolution copy of the source, for the crop and annotate
                // canvases.
                LaunchedEffect(source) {
                    val uri = source
                    sourceImage = if (uri == null) null else withContext(Dispatchers.IO) {
                        runCatching { ShrinkEngine.loadPreview(context, uri) }
                            .getOrNull()?.asImageBitmap()
                    }
                }

                LaunchedEffect(
                    source, width, policy, crop, manualQuality, annotations.items
                ) {
                    val uri = source ?: return@LaunchedEffect
                    // Debounce: the sliders fire continuously while dragging, and each
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
                                forcedQuality = manualQuality,
                                annotations = annotations.items,
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

                when {
                    annotating && img != null -> FullScreenEditor(
                        titleRes = R.string.annotate_title,
                    ) {
                        AnnotationEditor(
                            image = img,
                            state = annotations,
                            onStateChange = { annotations = it },
                            onApply = { annotating = false },
                            onCancel = { annotating = false },
                        )
                    }

                    cropping && img != null -> FullScreenEditor(
                        titleRes = R.string.crop_title,
                    ) {
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

                    else -> MainScreen(
                        state = state,
                        crop = crop,
                        width = width,
                        policy = policy,
                        manualQuality = manualQuality,
                        optionsExpanded = optionsExpanded,
                        canAnnotate = img != null,
                        canReplace = source?.let {
                            OriginalReplacer.isMediaStoreUri(it)
                        } == true,
                        saveMenuOpen = saveMenuOpen,
                        onOptionsExpandedChange = { optionsExpanded = it },
                        onWidthChange = { width = it },
                        onWidthSettled = { settings.outputWidth = width },
                        onQualityChange = {
                            manualQuality = it
                            settings.manualQuality = it
                        },
                        onPolicyChange = {
                            policy = it
                            settings.formatPolicy = it
                        },
                        onPick = { picker.launchImage() },
                        onCrop = { cropping = true },
                        onAnnotate = { annotating = true },
                        onReplace = {
                            saveMenuOpen = false
                            confirmReplace = true
                        },
                        onSaveMenu = { saveMenuOpen = true },
                        onSaveMenuDismiss = { saveMenuOpen = false },
                        onSaveAsNew = {
                            saveMenuOpen = false
                            (state as? UiState.Ready)?.let { ready ->
                                lifecycleScope.launch {
                                    toast = saveAsNew(ready.result)
                                }
                            }
                        },
                        onSend = { result -> sendResult(result) },
                        onTapPreview = { fullscreen = true },
                        onSelect = { selection -> crop = crop.compose(selection) },
                        onClearSelection = { crop = CropRect.FULL },
                        onHelp = {
                            startActivity(Intent(context, HelpActivity::class.java))
                        },
                        onSettings = {
                            startActivity(Intent(context, SettingsActivity::class.java))
                        },
                    )
                }

                // Destructive and unrecoverable, so it always asks first.
                if (confirmReplace) {
                    val ready = state as? UiState.Ready
                    val uri = source
                    if (ready != null && uri != null) {
                        val currentName = remember(uri) {
                            OriginalReplacer.queryName(context, uri)
                        }
                        val newName = currentName?.let {
                            OriginalReplacer.renameForFormat(it, ready.result.format)
                        }
                        val scope = rememberCoroutineScope()

                        AlertDialog(
                            onDismissRequest = { confirmReplace = false },
                            title = {
                                Text(stringResource(R.string.replace_confirm_title))
                            },
                            text = {
                                Column {
                                    Text(
                                        stringResource(
                                            R.string.replace_confirm_body,
                                            ready.result.format.label,
                                            formatBytes(ready.result.sourceBytes),
                                            formatBytes(ready.result.outputBytes),
                                        )
                                    )
                                    if (newName != null && newName != currentName) {
                                        Spacer(Modifier.height(10.dp))
                                        Text(
                                            stringResource(
                                                R.string.replace_confirm_rename,
                                                newName,
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme
                                                .onSurfaceVariant,
                                        )
                                    }
                                }
                            },
                            confirmButton = {
                                TextButton(onClick = {
                                    confirmReplace = false
                                    scope.launch {
                                        toast = performReplace(uri, ready.result)
                                    }
                                }) {
                                    Text(stringResource(R.string.replace_confirm_ok))
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { confirmReplace = false }) {
                                    Text(stringResource(R.string.cancel))
                                }
                            },
                        )
                    } else {
                        confirmReplace = false
                    }
                }

                toast?.let { message ->
                    LaunchedEffect(message) {
                        if (message.isNotEmpty()) {
                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        }
                        toast = null
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

    /** Bounded, non-scrolling host for the crop and annotate editors. */
    @Composable
    private fun FullScreenEditor(titleRes: Int, content: @Composable () -> Unit) {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(
                    stringResource(titleRes),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(6.dp))
                content()
            }
        }
    }

    @Composable
    private fun MainScreen(
        state: UiState,
        crop: CropRect,
        width: Int,
        policy: FormatPolicy,
        manualQuality: Int?,
        optionsExpanded: Boolean,
        canAnnotate: Boolean,
        canReplace: Boolean,
        saveMenuOpen: Boolean,
        onOptionsExpandedChange: (Boolean) -> Unit,
        onWidthChange: (Int) -> Unit,
        onWidthSettled: () -> Unit,
        onQualityChange: (Int?) -> Unit,
        onPolicyChange: (FormatPolicy) -> Unit,
        onPick: () -> Unit,
        onCrop: () -> Unit,
        onAnnotate: () -> Unit,
        onReplace: () -> Unit,
        onSaveMenu: () -> Unit,
        onSaveMenuDismiss: () -> Unit,
        onSaveAsNew: () -> Unit,
        onSend: (ShrinkResult) -> Unit,
        onTapPreview: () -> Unit,
        onSelect: (CropRect) -> Unit,
        onClearSelection: () -> Unit,
        onHelp: () -> Unit,
        onSettings: () -> Unit,
    ) {
        Surface(Modifier.fillMaxSize()) {
            // A bounded Column, NOT a scrolling one: the action bar is laid out first
            // and the preview takes what is left, so the bar can never be pushed off
            // the bottom edge.
            Column(
                Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .padding(horizontal = 16.dp),
            ) {
                // Compact title row.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.app_name),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onHelp) {
                        Text(stringResource(R.string.help), fontSize = 13.sp)
                    }
                    TextButton(onClick = onSettings) {
                        Text(stringResource(R.string.settings), fontSize = 13.sp)
                    }
                }

                OptionsSection(
                    expanded = optionsExpanded,
                    onExpandedChange = onOptionsExpandedChange,
                    width = width,
                    onWidthChange = onWidthChange,
                    onWidthSettled = onWidthSettled,
                    manualQuality = manualQuality,
                    actualQuality = (state as? UiState.Ready)?.result?.quality,
                    onQualityChange = onQualityChange,
                    policy = policy,
                    onPolicyChange = onPolicyChange,
                )

                Spacer(Modifier.height(8.dp))

                // The preview claims all remaining space.
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    when (state) {
                        is UiState.Empty -> EmptyState(state.reason, onPick)

                        is UiState.Working -> CircularProgressIndicator()

                        is UiState.Failed -> Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                state.message,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Spacer(Modifier.height(12.dp))
                            OutlinedButton(onClick = onPick) {
                                Text(stringResource(R.string.open_an_image))
                            }
                        }

                        is UiState.Ready -> PreviewPane(
                            result = state.result,
                            crop = crop,
                            onTapPreview = onTapPreview,
                            onSelect = onSelect,
                            onClearSelection = onClearSelection,
                        )
                    }
                }

                // Fixed-size icon actions, only when there is something to act on.
                if (state is UiState.Ready) {
                    Spacer(Modifier.height(6.dp))
                    ActionBar(Modifier.padding(bottom = 6.dp)) {
                        IconAction(
                            iconRes = R.drawable.ic_action_change,
                            labelRes = R.string.change,
                            onClick = onPick,
                        )
                        IconAction(
                            iconRes = R.drawable.ic_action_crop,
                            labelRes = R.string.adjust,
                            onClick = onCrop,
                            enabled = canAnnotate,
                        )
                        IconAction(
                            iconRes = R.drawable.ic_action_annotate,
                            labelRes = R.string.annotate,
                            onClick = onAnnotate,
                            enabled = canAnnotate,
                        )
                        // Save opens a small menu: overwriting is destructive and
                        // should not be one tap away from an adjacent button.
                        Box {
                            IconAction(
                                iconRes = R.drawable.ic_action_save,
                                labelRes = R.string.save,
                                onClick = onSaveMenu,
                            )
                            SaveMenu(
                                expanded = saveMenuOpen,
                                canOverwrite = canReplace,
                                onDismiss = onSaveMenuDismiss,
                                onOverwrite = onReplace,
                                onSaveAsNew = onSaveAsNew,
                            )
                        }
                        IconAction(
                            iconRes = R.drawable.ic_action_send,
                            labelRes = R.string.send,
                            onClick = { onSend(state.result) },
                            emphasised = true,
                            caption = formatBytes(state.result.outputBytes),
                        )
                    }
                }
            }
        }
    }

    /**
     * Two-option menu anchored above the Save action.
     *
     * Overwriting is irreversible, so it is deliberately behind this menu rather than
     * being its own button in the bar, where a mis-tap on a neighbouring icon could
     * destroy the original. Each option states its consequence.
     */
    @Composable
    private fun SaveMenu(
        expanded: Boolean,
        canOverwrite: Boolean,
        onDismiss: () -> Unit,
        onOverwrite: () -> Unit,
        onSaveAsNew: () -> Unit,
    ) {
        // No manual offset. An earlier version nudged the menu up by a hardcoded
        // -150dp, which only looked right for one menu height on one screen; Compose
        // already flips a DropdownMenu upwards when there is no room below, and anchors
        // it to this Box — which is the icon itself.
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismiss,
        ) {
            DropdownMenuItem(
                enabled = canOverwrite,
                onClick = onOverwrite,
                text = {
                    Column {
                        Text(stringResource(R.string.save_overwrite))
                        Text(
                            stringResource(R.string.save_overwrite_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                leadingIcon = {
                    Icon(
                        imageVector = ImageVector.vectorResource(
                            R.drawable.ic_action_replace
                        ),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                },
            )
            DropdownMenuItem(
                onClick = onSaveAsNew,
                text = {
                    Column {
                        Text(stringResource(R.string.save_as_new))
                        Text(
                            stringResource(
                                R.string.save_as_new_desc, MediaStoreSaver.ALBUM
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                leadingIcon = {
                    Icon(
                        imageVector = ImageVector.vectorResource(
                            R.drawable.ic_action_save
                        ),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                },
            )
        }
    }

    /** Preview image plus a single compact stats line. */
    @Composable
    private fun PreviewPane(
        result: ShrinkResult,
        crop: CropRect,
        onTapPreview: () -> Unit,
        onSelect: (CropRect) -> Unit,
        onClearSelection: () -> Unit,
    ) {
        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val bitmap = remember(result) {
                BitmapFactory.decodeByteArray(result.bytes, 0, result.bytes.size)
            }
            if (bitmap != null) {
                val lasso = rememberLassoState()
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(MaterialTheme.colorScheme.previewBackdrop)
                        .clickable { onTapPreview() }
                        .drawLasso(
                            state = lasso,
                            imageWidth = result.width,
                            imageHeight = result.height,
                            enabled = true,
                            onSelected = onSelect,
                        ),
                    contentScale = ContentScale.Fit,
                )
            }

            Spacer(Modifier.height(6.dp))

            // One line instead of the previous three-column block: before → after,
            // the saving, and the encoder settled on.
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            formatBytes(result.sourceBytes),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "  →  ",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            formatBytes(result.outputBytes),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        if (result.sourceBytes > 0) {
                            Text(
                                "   " + stringResource(
                                    R.string.saved_percent,
                                    ((1f - result.ratio) * 100).toInt(),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    Text(
                        "${result.width}×${result.height} · " +
                            "${result.format.label} q${result.quality}",
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Only shown when there is a selection to clear.
                if (!crop.isFullFrame) {
                    TextButton(onClick = onClearSelection) {
                        Text(stringResource(R.string.clear_selection), fontSize = 12.sp)
                    }
                }
            }

            if (result.budgetMissed) {
                Text(
                    stringResource(R.string.budget_missed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }

    @Composable
    private fun EmptyState(message: String, onPick: () -> Unit) {
        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(message, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                Button(onClick = onPick) {
                    Icon(
                        imageVector = ImageVector.vectorResource(
                            R.drawable.ic_action_change
                        ),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.open_an_image))
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.usage_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    /**
     * Overwrite the original, asking the system for consent if required.
     *
     * Android 10+ refuses to let an app modify media it did not create without explicit
     * user approval, delivered as a RecoverableSecurityException carrying a consent
     * dialog. The pending write is stashed so it can be retried once the user agrees.
     */
    private suspend fun performReplace(uri: Uri, result: ShrinkResult): String =
        withContext(Dispatchers.IO) {
            when (
                val outcome = OriginalReplacer.replace(this@ShrinkActivity, uri, result)
            ) {
                is ReplaceResult.Success -> getString(R.string.replace_done)

                is ReplaceResult.NeedsPermission -> {
                    pendingReplace = uri to result
                    withContext(Dispatchers.Main) {
                        OriginalReplacer.requestConsent(
                            this@ShrinkActivity,
                            outcome.intentSender,
                            REQUEST_REPLACE_CONSENT,
                        )
                    }
                    "" // No message: the system dialog is now in front of the user.
                }

                is ReplaceResult.Failed -> getString(
                    R.string.replace_failed, outcome.reason
                )
            }
        }

    @Deprecated("Needed for startIntentSenderForResult consent callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_REPLACE_CONSENT) return

        val pending = pendingReplace ?: return
        pendingReplace = null
        if (resultCode != Activity.RESULT_OK) return

        // Consent granted: the same write now succeeds.
        lifecycleScope.launch {
            val message = withContext(Dispatchers.IO) {
                when (
                    OriginalReplacer.replace(
                        this@ShrinkActivity, pending.first, pending.second
                    )
                ) {
                    is ReplaceResult.Success -> getString(R.string.replace_done)
                    is ReplaceResult.Failed,
                    is ReplaceResult.NeedsPermission ->
                        getString(R.string.replace_failed, "denied")
                }
            }
            Toast.makeText(this@ShrinkActivity, message, Toast.LENGTH_SHORT).show()
        }
    }

    /** Write a NEW file into the Suotu album, leaving the original untouched. */
    private suspend fun saveAsNew(result: ShrinkResult): String =
        withContext(Dispatchers.IO) {
            val base = "Suotu_${System.currentTimeMillis()}"
            val uri = MediaStoreSaver.save(this@ShrinkActivity, result, base)
            if (uri != null) {
                getString(R.string.saved_as_new, MediaStoreSaver.ALBUM)
            } else {
                getString(R.string.save_as_new_failed)
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
    private inline fun <reified T : android.os.Parcelable>
        Intent.parcelableArrayListExtra(
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
