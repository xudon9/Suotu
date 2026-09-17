package com.xudong.suotu

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Editor for one list of [WatchRule]s.
 *
 * Used twice with different lists — the silent watch list and the open-detect list —
 * because those genuinely want different folders.
 */
@Composable
fun RuleListEditor(
    title: String,
    explanation: String,
    rules: List<WatchRule>,
    onChange: (List<WatchRule>) -> Unit,
    suggestions: List<Pair<String, String>> = emptyList(),
) {
    var pickerFor by remember { mutableStateOf<Int?>(null) }
    var testFor by remember { mutableStateOf<Int?>(null) }

    Column(Modifier.fillMaxWidth()) {
        Text(title, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(2.dp))
        Text(
            explanation,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))

        rules.forEachIndexed { index, rule ->
            RuleRow(
                rule = rule,
                onChange = { updated ->
                    onChange(rules.toMutableList().also { it[index] = updated })
                },
                onDelete = {
                    onChange(rules.toMutableList().also { it.removeAt(index) })
                },
                onChoose = { pickerFor = index },
                onTest = { testFor = index },
            )
            Spacer(Modifier.height(8.dp))
        }

        if (rules.isEmpty()) {
            Text(
                stringResource(R.string.rule_none),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(8.dp))
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = {
                onChange(rules + WatchRule("Pictures", WatchRule.ANY_IMAGE_REGEX))
                pickerFor = rules.size
            }) { Text("+ " + stringResource(R.string.rule_add)) }

            suggestions.forEach { (label, path) ->
                if (rules.none { it.normalizedPath.equals(path, true) }) {
                    AssistChip(
                        onClick = {
                            val pattern = if (path.contains("Screenshot", true)) {
                                WatchRule.SCREENSHOT_REGEX
                            } else {
                                WatchRule.ANY_IMAGE_REGEX
                            }
                            onChange(rules + WatchRule(path, pattern))
                        },
                        label = { Text(label) },
                    )
                }
            }
        }
    }

    pickerFor?.let { index ->
        FolderPickerDialog(
            onPick = { path ->
                rules.getOrNull(index)?.let { rule ->
                    onChange(
                        rules.toMutableList()
                            .also { it[index] = rule.copy(path = path) }
                    )
                }
                pickerFor = null
            },
            onDismiss = { pickerFor = null },
        )
    }

    testFor?.let { index ->
        rules.getOrNull(index)?.let { rule ->
            PatternTestDialog(rule = rule, onDismiss = { testFor = null })
        }
    }
}

@Composable
private fun RuleRow(
    rule: WatchRule,
    onChange: (WatchRule) -> Unit,
    onDelete: () -> Unit,
    onChoose: () -> Unit,
    onTest: () -> Unit,
) {
    val badRegex = rule.regex == null && rule.pattern.isNotBlank()

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = rule.path,
                    onValueChange = { onChange(rule.copy(path = it)) },
                    label = { Text(stringResource(R.string.rule_folder)) },
                    placeholder = { Text("Pictures/Screenshots") },
                    singleLine = true,
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace, fontSize = 13.sp
                    ),
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = rule.enabled,
                    onCheckedChange = { onChange(rule.copy(enabled = it)) },
                )
            }

            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = rule.pattern,
                onValueChange = { onChange(rule.copy(pattern = it)) },
                label = { Text(stringResource(R.string.rule_pattern)) },
                singleLine = true,
                isError = badRegex,
                supportingText = {
                    Text(
                        if (badRegex) stringResource(R.string.rule_pattern_invalid)
                        else stringResource(R.string.rule_pattern_help),
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                textStyle = TextStyle(
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onChoose) {
                    Text(stringResource(R.string.rule_choose))
                }
                TextButton(onClick = onTest) {
                    Text(stringResource(R.string.rule_test))
                }
                TextButton(onClick = onDelete) {
                    Text(stringResource(R.string.rule_remove))
                }
            }
        }
    }
}
