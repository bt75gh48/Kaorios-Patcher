package com.kaorios.patcher.ui.screen.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kaorios.patcher.R
import com.kaorios.patcher.data.settings.EditorPrefs
import kotlinx.coroutines.launch

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snapshot by remember { EditorPrefs.flow(ctx) }.collectAsState(initial = EditorPrefs.Snapshot())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                }
            )
        }
    ) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(16.dp)
        ) {
            Text(
                stringResource(R.string.settings_section_display),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.size(8.dp))

            Text(
                stringResource(R.string.settings_font_size_fmt, snapshot.fontSize.toInt()),
                style = MaterialTheme.typography.bodyMedium
            )
            Slider(
                value = snapshot.fontSize,
                onValueChange = { v ->
                    scope.launch { EditorPrefs.update(ctx) { copy(fontSize = v) } }
                },
                valueRange = 10f..28f,
                steps = 17
            )

            SwitchRow(
                label = stringResource(R.string.settings_word_wrap),
                checked = snapshot.wordWrap,
                onChanged = { v -> scope.launch { EditorPrefs.update(ctx) { copy(wordWrap = v) } } }
            )
            SwitchRow(
                label = stringResource(R.string.settings_line_number),
                checked = snapshot.lineNumber,
                onChanged = { v -> scope.launch { EditorPrefs.update(ctx) { copy(lineNumber = v) } } }
            )
            SwitchRow(
                label = stringResource(R.string.settings_tab_indent),
                checked = snapshot.tabIndent,
                onChanged = { v -> scope.launch { EditorPrefs.update(ctx) { copy(tabIndent = v) } } }
            )

            Spacer(Modifier.size(16.dp))
            Text(
                stringResource(R.string.settings_section_behavior),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.size(8.dp))
            SwitchRow(
                label = stringResource(R.string.settings_auto_save),
                checked = snapshot.autoSave,
                onChanged = { v -> scope.launch { EditorPrefs.update(ctx) { copy(autoSave = v) } } }
            )

            Spacer(Modifier.size(16.dp))
            Text(
                stringResource(R.string.settings_section_theme),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.size(8.dp))
            val themeKeys = listOf("system", "dark", "light")
            val themeLabels = listOf(
                stringResource(R.string.settings_theme_system),
                stringResource(R.string.settings_theme_dark),
                stringResource(R.string.settings_theme_light)
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                themeKeys.forEachIndexed { idx, key ->
                    SegmentedButton(
                        selected = snapshot.themeOverride == key,
                        onClick = {
                            scope.launch { EditorPrefs.update(ctx) { copy(themeOverride = key) } }
                        },
                        shape = SegmentedButtonDefaults.itemShape(index = idx, count = themeKeys.size)
                    ) { Text(themeLabels[idx]) }
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChanged: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChanged)
    }
}
