package com.xudong.suotu

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

/** Permissions the watcher needs, for the current API level. */
private fun requiredPermissions(): Array<String> = buildList {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        add(Manifest.permission.READ_MEDIA_IMAGES)
        add(Manifest.permission.POST_NOTIFICATIONS)
    } else {
        add(Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}.toTypedArray()

/** True if the app may read the gallery (needed to find the newest image). */
fun hasMediaReadPermission(context: Context): Boolean {
    val key = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    return ContextCompat.checkSelfPermission(context, key) == PackageManager.PERMISSION_GRANTED
}

/** Full settings screen: both rule lists, the recency threshold, and the watcher toggle. */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val settings = Settings(this)

        setContent {
            SuotuTheme {
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .safeDrawingPadding()
                            .padding(20.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(
                            stringResource(R.string.settings),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(20.dp))

                        LanguageSection()
                        Spacer(Modifier.height(20.dp))
                        AutoShrinkSection(settings)
                        Spacer(Modifier.height(20.dp))
                        OpenDetectSection(settings)
                        Spacer(Modifier.height(32.dp))
                    }
                }
            }
        }
    }
}

/** The silent background watcher and the folders it watches. */
@Composable
private fun AutoShrinkSection(settings: Settings) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(settings.autoShrinkEnabled) }
    var notify by remember { mutableStateOf(settings.autoShrinkNotify) }
    var rules by remember { mutableStateOf(settings.autoShrinkRules) }
    var denied by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        if (hasMediaReadPermission(context)) {
            denied = false
            enabled = true
            settings.autoShrinkEnabled = true
            ScreenshotWatcherJob.schedule(context)
        } else {
            denied = true
            enabled = false
            settings.autoShrinkEnabled = false
        }
    }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.auto_shrink_title), fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        stringResource(R.string.auto_shrink_desc, MediaStoreSaver.ALBUM),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = enabled,
                    onCheckedChange = { want ->
                        if (!want) {
                            enabled = false
                            settings.autoShrinkEnabled = false
                            ScreenshotWatcherJob.cancel(context)
                        } else if (hasMediaReadPermission(context)) {
                            enabled = true
                            settings.autoShrinkEnabled = true
                            ScreenshotWatcherJob.schedule(context)
                        } else {
                            permissionLauncher.launch(requiredPermissions())
                        }
                    },
                )
            }

            if (denied) {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.perm_required),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            if (enabled) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.notify_title), style = MaterialTheme.typography.bodyMedium)
                        Text(
                            stringResource(R.string.notify_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = notify,
                        onCheckedChange = {
                            notify = it
                            settings.autoShrinkNotify = it
                        },
                    )
                }

                Spacer(Modifier.height(16.dp))
                Divider()
                Spacer(Modifier.height(16.dp))

                RuleListEditor(
                    title = stringResource(R.string.folders_watch_title),
                    explanation = stringResource(R.string.folders_watch_desc),
                    rules = rules,
                    onChange = {
                        rules = it
                        settings.autoShrinkRules = it
                    },
                    suggestions = listOf(
                        "Screenshots" to WatchRule.SCREENSHOTS_DIR,
                        "Camera" to WatchRule.CAMERA_DIR,
                    ),
                )
            }
        }
    }
}

/** What the app offers when you open it. */
@Composable
private fun OpenDetectSection(settings: Settings) {
    var detect by remember { mutableStateOf(settings.openDetectEnabled) }
    var recency by remember { mutableStateOf(settings.openRecencySeconds) }
    var rules by remember { mutableStateOf(settings.openRules) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.open_detect_title), fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        stringResource(R.string.open_detect_desc),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(
                    checked = detect,
                    onCheckedChange = {
                        detect = it
                        settings.openDetectEnabled = it
                    },
                )
            }

            if (detect) {
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.open_if_newer), fontWeight = FontWeight.Medium)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(30, 60, 180, 600).forEach { secs ->
                        FilterChip(
                            selected = recency == secs,
                            onClick = {
                                recency = secs
                                settings.openRecencySeconds = secs
                            },
                            label = { Text(formatDuration(secs.toLong())) },
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))
                Divider()
                Spacer(Modifier.height(16.dp))

                RuleListEditor(
                    title = stringResource(R.string.folders_offer_title),
                    explanation = stringResource(R.string.folders_offer_desc),
                    rules = rules,
                    onChange = {
                        rules = it
                        settings.openRules = it
                    },
                    suggestions = listOf(
                        "Screenshots" to WatchRule.SCREENSHOTS_DIR,
                        "Camera" to WatchRule.CAMERA_DIR,
                    ),
                )
            }
        }
    }
}

/** UI language: follow the system, or force English / 中文. */
@Composable
private fun LanguageSection() {
    val context = LocalContext.current
    var current by remember { mutableStateOf(LocaleManager.current()) }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.language_title),
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AppLanguage.entries.forEach { lang ->
                    FilterChip(
                        selected = lang == current,
                        onClick = {
                            current = lang
                            // AppCompat persists this and recreates the activity.
                            LocaleManager.apply(lang)
                        },
                        label = { Text(LocaleManager.label(context, lang)) },
                    )
                }
            }
        }
    }
}
