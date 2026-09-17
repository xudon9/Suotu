package com.xudong.suotu

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
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
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Explains what the app does, how to use it, and why the defaults are what they are. */
class HelpActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

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
                            stringResource(R.string.help_title),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            stringResource(R.string.help_intro),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(20.dp))

                        Section(R.string.help_quick_title, R.string.help_quick_body)
                        Section(R.string.help_size_title, R.string.help_size_body)
                        Section(R.string.help_format_title, R.string.help_format_body)
                        Section(R.string.help_auto_title, R.string.help_auto_body)
                        Section(R.string.help_rules_title, R.string.help_rules_body)
                        Section(R.string.help_privacy_title, R.string.help_privacy_body)
                        Section(R.string.help_note_title, R.string.help_note_body)

                        AboutSection()

                        Spacer(Modifier.height(32.dp))
                    }
                }
            }
        }
    }

    /**
     * App identity, licence and source link.
     *
     * The version is read from the package manager rather than BuildConfig so it can
     * never drift from what is actually installed.
     */
    @Composable
    private fun AboutSection() {
        val context = LocalContext.current
        val clipboard = LocalClipboardManager.current
        val uriHandler = LocalUriHandler.current

        val versionName = remember {
            runCatching {
                context.packageManager
                    .getPackageInfo(context.packageName, 0).versionName
            }.getOrNull() ?: "?"
        }
        val sourceUrl = stringResource(R.string.about_source_url)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    stringResource(R.string.about_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))

                Text(
                    stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    stringResource(R.string.about_tagline),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.about_version, versionName),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    stringResource(R.string.about_package, context.packageName),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.about_license),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.about_built_with),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.about_no_network),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = {
                        // No network permission, so this hands off to the browser.
                        runCatching { uriHandler.openUri(sourceUrl) }
                    }) { Text(stringResource(R.string.about_source)) }

                    TextButton(onClick = {
                        clipboard.setText(
                            AnnotatedString(
                                "${context.getString(R.string.app_name)} " +
                                    "$versionName (${context.packageName})\n$sourceUrl"
                            )
                        )
                    }) { Text(stringResource(R.string.about_copy)) }
                }

                Text(
                    sourceUrl,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
    }

    @Composable
    private fun Section(titleRes: Int, bodyRes: Int) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(
                    stringResource(titleRes),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(bodyRes),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}
