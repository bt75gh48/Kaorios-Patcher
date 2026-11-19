package com.kaorios.patcher.ui.screen.picker

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kaorios.patcher.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kaorios.patcher.data.framework.SystemFrameworkReader
import com.kaorios.patcher.domain.model.WorkMode
import com.kaorios.patcher.util.FileIo

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun JarPickerScreen(
    mode: WorkMode,
    onBack: () -> Unit,
    onReady: (workDir: String) -> Unit,
    vm: JarPickerViewModel = viewModel()
) {
    val ctx = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(mode) { vm.setMode(mode) }

    // Cancel build when user presses back or leaves screen
    DisposableEffect(Unit) {
        onDispose { vm.cancelBuild() }
    }

    var activeIndex by rememberSaveable { mutableIntStateOf(0) }
    var showSystemPicker by remember { mutableStateOf(false) }
    var systemMissing by remember { mutableStateOf<String?>(null) }

    val safPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            val name = FileIo.queryDisplayName(ctx.contentResolver, uri) ?: "input.jar"
            vm.onSafPicked(activeIndex, uri, name)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val modeName = stringResource(
                        id = when (mode) {
                            WorkMode.KAORIOS_PATCHER -> R.string.mode_kaorios_title
                            WorkMode.OTHER_JAR -> R.string.mode_other_title
                        }
                    )
                    Text(stringResource(R.string.picker_title_fmt, modeName))
                },
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                stringResource(
                    when (mode) {
                        WorkMode.KAORIOS_PATCHER -> R.string.picker_hint_kaorios
                        WorkMode.OTHER_JAR -> R.string.picker_hint_other
                    }
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            state.entries.forEachIndexed { idx, entry ->
                PickRow(
                    label = labelFor(mode, idx, entry.logicalName),
                    value = entry.displayName,
                    picked = entry.source != null,
                    enabled = !state.running,
                    showSystemPicker = state.systemFrameworkAvailable,
                    onPickSaf = {
                        activeIndex = idx
                        safPicker.launch(arrayOf("application/java-archive", "application/octet-stream", "*/*"))
                    },
                    onPickSystem = {
                        activeIndex = idx
                        if (mode == WorkMode.KAORIOS_PATCHER) {
                            val targetName = if (idx == 0) "framework.jar" else "services.jar"
                            val path = "/system/framework/$targetName"
                            val f = java.io.File(path)
                            if (f.isFile && f.canRead()) {
                                vm.onSystemPicked(idx, path, targetName)
                            } else {
                                systemMissing = path
                            }
                        } else {
                            showSystemPicker = true
                        }
                    }
                )
            }

            if (state.running) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    if (state.progressTotal > 0) "${state.progressStage} (${state.progressCurrent}/${state.progressTotal})"
                    else state.progressStage,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            state.error?.let { err ->
                Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.running && state.entries.all { it.source != null },
                onClick = { vm.start(onReady) }
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.picker_start))
            }
        }
    }

    if (showSystemPicker) {
        SystemFrameworkPickerDialog(
            onDismiss = { showSystemPicker = false },
            onPick = { entry ->
                vm.onSystemPicked(activeIndex, entry.file.absolutePath, entry.file.name)
                showSystemPicker = false
            },
            loader = { vm.listSystemFramework() }
        )
    }

    systemMissing?.let { path ->
        AlertDialog(
            onDismissRequest = { systemMissing = null },
            title = { Text(stringResource(R.string.picker_system_missing_title)) },
            text = {
                Column {
                    Text(stringResource(R.string.picker_system_missing_body_fmt, path))
                    Spacer(Modifier.size(8.dp))
                    Text(
                        stringResource(R.string.picker_system_missing_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { systemMissing = null }) { Text(stringResource(R.string.action_close)) }
            }
        )
    }
}

private fun labelFor(mode: WorkMode, index: Int, logicalName: String): String =
    when (mode) {
        WorkMode.KAORIOS_PATCHER -> if (index == 0) "framework.jar" else "services.jar"
        else -> "$logicalName.jar"
    }

@Composable
private fun PickRow(
    label: String,
    value: String,
    picked: Boolean,
    enabled: Boolean,
    showSystemPicker: Boolean,
    onPickSaf: () -> Unit,
    onPickSystem: () -> Unit
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.FolderOpen, contentDescription = null)
                Spacer(Modifier.size(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(label, fontWeight = FontWeight.SemiBold)
                    Text(
                        value,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (picked) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onPickSaf,
                    enabled = enabled,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(stringResource(R.string.picker_source_storage))
                }
                if (showSystemPicker) {
                    OutlinedButton(
                        onClick = onPickSystem,
                        enabled = enabled,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Filled.PhoneAndroid, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(stringResource(R.string.picker_source_system))
                    }
                }
            }
        }
    }
}

@Composable
private fun SystemFrameworkPickerDialog(
    onDismiss: () -> Unit,
    onPick: (SystemFrameworkReader.Entry) -> Unit,
    loader: () -> List<SystemFrameworkReader.Entry>
) {
    val items = remember { loader() }
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, items) {
        if (query.isBlank()) items
        else items.filter { it.file.name.contains(query, ignoreCase = true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.picker_system_dialog_title, items.size)) },
        text = {
            Column(Modifier.heightIn(min = 200.dp, max = 480.dp)) {
                Text(
                    stringResource(R.string.picker_system_dialog_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.size(8.dp))
                androidx.compose.material3.OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.picker_system_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.size(8.dp))
                if (items.isEmpty()) {
                    Text(
                        stringResource(R.string.picker_system_unreadable),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    LazyColumn {
                        items(filtered) { entry ->
                            Surface(
                                onClick = { onPick(entry) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(Modifier.padding(horizontal = 8.dp, vertical = 10.dp)) {
                                    Text(
                                        entry.file.name,
                                        fontFamily = FontFamily.Monospace,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        "${entry.sizeBytes / 1024} KB",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } }
    )
}
