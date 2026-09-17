package com.xudong.suotu

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Folder chooser.
 *
 * Lists folders that actually contain images — typing a path by hand is easy to get
 * subtly wrong, and a wrong path fails silently by matching nothing. "Browse…" falls
 * back to the system picker for folders that hold no images yet.
 */
@Composable
fun FolderPickerDialog(
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var folders by remember { mutableStateOf<List<ImageFolder>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val unsupported = stringResource(R.string.folder_unsupported)
    val browse = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val path = FolderScanner.treeUriToRelativePath(uri)
        if (path != null) onPick(path) else error = unsupported
    }

    LaunchedEffect(Unit) {
        folders = withContext(Dispatchers.IO) {
            FolderScanner.listImageFolders(context)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.folder_picker_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.folder_picker_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                error?.let {
                    Spacer(Modifier.padding(top = 8.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.padding(top = 12.dp))

                val list = folders
                when {
                    list == null -> Text("…")
                    list.isEmpty() -> Text(
                        stringResource(R.string.folder_picker_empty),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    else -> LazyColumn(Modifier.heightIn(max = 340.dp)) {
                        items(list) { folder ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onPick(folder.path) }
                                    .padding(vertical = 10.dp),
                            ) {
                                Text(
                                    folder.path,
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Medium,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    stringResource(
                                        R.string.folder_picker_images, folder.imageCount
                                    ) + (folder.sampleName?.let { " · $it" } ?: ""),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Divider()
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = { browse.launch(null) }) {
                    Text(stringResource(R.string.folder_picker_browse))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            }
        },
    )
}

/**
 * Shows the file names in a folder and whether the pattern matches each one.
 *
 * A regex is guesswork until it is tried against real names — this makes the rule
 * verifiable before it is relied upon.
 */
@Composable
fun PatternTestDialog(
    rule: WatchRule,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var names by remember { mutableStateOf<List<String>?>(null) }

    LaunchedEffect(rule.path) {
        names = withContext(Dispatchers.IO) {
            FolderScanner.listFileNames(context, rule.path)
        }
    }

    val regex = rule.regex
    val list = names.orEmpty()
    val matched = list.count { regex?.matches(it) == true }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.test_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.test_desc, rule.normalizedPath),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.padding(top = 6.dp))

                if (regex == null) {
                    Text(
                        stringResource(R.string.rule_pattern_invalid),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else {
                    Text(
                        stringResource(R.string.test_matched, matched, list.size),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                Spacer(Modifier.padding(top = 10.dp))

                when {
                    names == null -> Text("…")
                    list.isEmpty() -> Text(
                        stringResource(R.string.test_empty),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    else -> LazyColumn(Modifier.heightIn(max = 340.dp)) {
                        items(list) { name ->
                            val hit = regex?.matches(name) == true
                            Row(Modifier.padding(vertical = 5.dp)) {
                                Text(
                                    if (hit) "✓ " else "✗ ",
                                    color = if (hit) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    name,
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (hit) {
                                        MaterialTheme.colorScheme.onSurface
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.close)) }
        },
    )
}
