package com.kaorios.patcher.ui.screen.browser

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.res.stringResource
import com.kaorios.patcher.R
import com.kaorios.patcher.data.log.LogSink
import com.kaorios.patcher.domain.actions.ActionState
import com.kaorios.patcher.domain.actions.ActionsRegistry
import com.kaorios.patcher.domain.actions.ScriptGroup
import com.kaorios.patcher.domain.actions.scripts.DexRedivisionScript
import com.kaorios.patcher.domain.actions.scripts.DisableSecureFlagScript
import com.kaorios.patcher.domain.actions.scripts.RemoveHooksScript
import com.kaorios.patcher.domain.actions.scripts.UpdateKaoriosDexScript
import com.kaorios.patcher.domain.model.WorkMode
import com.kaorios.patcher.domain.usecase.BuildUseCase
import kotlinx.coroutines.launch
import java.io.File

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun FileBrowserScreen(
    workDir: String,
    onBack: () -> Unit,
    onOpenFile: (String) -> Unit,
    onOpenLog: () -> Unit,
    vm: FileBrowserViewModel = viewModel()
) {
    val ctx = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(workDir) { vm.load(workDir) }

    var actionMenuExpanded by remember { mutableStateOf(false) }
    var showBuildDialog by remember { mutableStateOf(false) }
    var showDexMarkDialog by remember { mutableStateOf(false) }
    var showExitConfirm by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showImportRootPicker by remember { mutableStateOf(false) }
    var showScriptPicker by remember { mutableStateOf(false) }
    var searchExpanded by remember { mutableStateOf(false) }
    var pendingQuery by remember { mutableStateOf("") }
    var importTargetRoot by remember { mutableStateOf<File?>(null) }

    var pendingBuildKind by remember { mutableStateOf<BuildUseCase.BuildKind?>(null) }

    val actionState by vm.actionState.collectAsStateWithLifecycle()
    val tagInput by vm.tagInput.collectAsStateWithLifecycle()
    val limitInput by vm.limitInput.collectAsStateWithLifecycle()
    val warn by vm.warn.collectAsStateWithLifecycle()
    LaunchedEffect(actionState.results) {
        actionState.results.lastOrNull()?.let { last ->
            if (!actionState.running) {
                val msg = if (last.ok) {
                    ctx.getString(
                        R.string.actions_ok_fmt,
                        actionState.results.count { it.ok },
                        actionState.results.size
                    )
                } else {
                    ctx.getString(
                        R.string.actions_error_fmt,
                        last.error ?: ctx.getString(R.string.browser_unknown_error)
                    )
                }
                snackbar.showSnackbar(msg)
                vm.consumeActionResults()
            }
        }
    }

    BackHandler(enabled = true) {
        when {
            state.selectionMode -> vm.clearSelection()
            searchExpanded -> {
                searchExpanded = false
                pendingQuery = ""
                vm.clearQuery()
            }
            else -> showExitConfirm = true
        }
    }

    val logExport = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) scope.launch {
            runCatching { LogSink.export(ctx, uri) }
                .onSuccess { snackbar.showSnackbar(ctx.getString(R.string.log_exported)) }
                .onFailure {
                    snackbar.showSnackbar(ctx.getString(R.string.log_save_failed_fmt, it.message ?: ""))
                }
        }
    }

    val treePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val kind = pendingBuildKind ?: BuildUseCase.BuildKind.JAR
        pendingBuildKind = null
        if (uri != null) {
            runCatching {
                ctx.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            vm.build(uri, kind)
        }
        // Cancel folder picker → không làm gì, ở lại màn hình hiện tại
    }

    val importPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        val root = importTargetRoot
        if (uri != null && root != null) {
            vm.importDex(root, uri) { ok, msg ->
                scope.launch { snackbar.showSnackbar(msg) }
            }
        }
        importTargetRoot = null
    }

    val exportPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        if (uri != null) {
            vm.exportSelectedAsDex(uri) { _, msg -> scope.launch { snackbar.showSnackbar(msg) } }
        }
    }

    LaunchedEffect(state.buildDone, state.buildError) {
        state.buildDone?.let { snackbar.showSnackbar(it); vm.consumeBuildNotice() }
        state.buildError?.let {
            snackbar.showSnackbar(ctx.getString(R.string.build_fail_fmt, it))
            vm.consumeBuildNotice()
        }
    }
    LaunchedEffect(state.toast) {
        state.toast?.let { snackbar.showSnackbar(it); vm.consumeToast() }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (state.selectionMode) {
                            stringResource(R.string.browser_title_selection_fmt, state.selectedKeys.size)
                        } else {
                            stringResource(R.string.browser_title_workspace)
                        },
                        style = MaterialTheme.typography.titleMedium
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (state.selectionMode) vm.clearSelection()
                        else showExitConfirm = true
                    }) {
                        Icon(
                            if (state.selectionMode) Icons.Filled.Close
                            else Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null
                        )
                    }
                },
                actions = {
                    IconButton(onClick = {
                        logExport.launch("kaorios_log_${System.currentTimeMillis()}.txt")
                    }) { Icon(Icons.AutoMirrored.Filled.Article, contentDescription = stringResource(R.string.cd_save_log)) }
                    IconButton(onClick = { showBuildDialog = true }) {
                        Icon(Icons.Filled.Build, contentDescription = stringResource(R.string.cd_build))
                    }
                    Box {
                        IconButton(onClick = { actionMenuExpanded = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.cd_actions))
                        }
                        DropdownMenu(
                            expanded = actionMenuExpanded,
                            onDismissRequest = { actionMenuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(stringResource(R.string.browser_menu_toggle_virtual), modifier = Modifier.weight(1f))
                                        Switch(
                                            checked = state.virtualMerge,
                                            onCheckedChange = {
                                                actionMenuExpanded = false
                                                vm.toggleVirtualMerge()
                                            }
                                        )
                                    }
                                },
                                onClick = {
                                    actionMenuExpanded = false
                                    vm.toggleVirtualMerge()
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.browser_menu_log)) },
                                onClick = { actionMenuExpanded = false; onOpenLog() }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.browser_menu_mark_dex)) },
                                onClick = {
                                    actionMenuExpanded = false
                                    showDexMarkDialog = true
                                }
                            )
                        }
                    }
                }
            )
        }
    ) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
        ) {
            BrowserToolbar(
                searchExpanded = searchExpanded,
                onToggleSearch = {
                    searchExpanded = !searchExpanded
                    if (!searchExpanded) {
                        pendingQuery = ""; vm.clearQuery(); keyboard?.hide()
                    }
                },
                selectionCount = state.selectedKeys.size,
                onImport = {
                    val roots = state.roots.map { it.rootDecompDir }
                    when {
                        roots.isEmpty() -> {
                            scope.launch {
                                snackbar.showSnackbar(ctx.getString(R.string.browser_no_workspace_snackbar))
                            }
                        }
                        roots.size == 1 -> {
                            importTargetRoot = roots[0]
                            importPicker.launch(arrayOf("*/*"))
                        }
                        else -> showImportRootPicker = true
                    }
                },
                onDelete = { showDeleteConfirm = true },
                onExport = {
                    exportPicker.launch("merged_${System.currentTimeMillis()}.dex")
                },
                onActions = { showScriptPicker = true },
                actionsRunning = actionState.running
            )

            AnimatedVisibility(searchExpanded) {
                OutlinedTextField(
                    value = pendingQuery,
                    onValueChange = { pendingQuery = it },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (pendingQuery.isNotEmpty()) {
                            IconButton(onClick = {
                                pendingQuery = ""; vm.clearQuery(); keyboard?.hide()
                            }) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.cd_clear)) }
                        }
                    },
                    placeholder = { Text(stringResource(R.string.browser_search_placeholder)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        vm.submitQuery(pendingQuery.trim()); keyboard?.hide()
                    }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }

            if (state.query.isNotBlank()) {
                Text(
                    stringResource(
                        R.string.browser_search_count_fmt,
                        state.query,
                        state.visibleNodes.size
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                )
            }

            AnimatedVisibility(state.building || state.searching || state.loading) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                ) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(
                        when {
                            state.loading -> stringResource(R.string.browser_loading)
                            state.searching -> stringResource(R.string.browser_searching_fmt, state.query)
                            else -> state.buildStage
                        },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }

            HorizontalDivider()

            when {
                state.visibleNodes.isEmpty() && !state.loading -> EmptyBrowser(state)
                state.mode == WorkMode.KAORIOS_PATCHER && state.query.isBlank() ->
                    SplitTree(state, vm, onOpenFile)
                else -> SingleTree(state, vm, onOpenFile)
            }
        }
    }

    if (showExitConfirm) {
        AlertDialog(
            onDismissRequest = { showExitConfirm = false },
            title = { Text(stringResource(R.string.browser_exit_confirm_title)) },
            text = { Text(stringResource(R.string.browser_exit_confirm_body)) },
            confirmButton = {
                TextButton(onClick = { showExitConfirm = false; onBack() }) {
                    Text(stringResource(R.string.action_exit))
                }
            },
            dismissButton = {
                TextButton(onClick = { showExitConfirm = false }) {
                    Text(stringResource(R.string.action_stay))
                }
            }
        )
    }

    if (showBuildDialog) {
        if (state.mode == WorkMode.KAORIOS_PATCHER) {
            AlertDialog(
                onDismissRequest = { showBuildDialog = false },
                title = { Text(stringResource(R.string.browser_build_dialog_kaorios_title)) },
                text = {
                    Column {
                        Text(
                            stringResource(R.string.browser_build_desc_jar),
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(
                            stringResource(R.string.browser_build_desc_module),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        showBuildDialog = false
                        pendingBuildKind = BuildUseCase.BuildKind.MODULE
                        treePicker.launch(null)
                    }) { Text(stringResource(R.string.browser_build_module_button)) }
                },
                dismissButton = {
                    Row {
                        TextButton(onClick = {
                            showBuildDialog = false
                            pendingBuildKind = BuildUseCase.BuildKind.JAR
                            treePicker.launch(null)
                        }) { Text(stringResource(R.string.browser_build_jar_button)) }
                        TextButton(onClick = { showBuildDialog = false }) {
                            Text(stringResource(R.string.action_cancel))
                        }
                    }
                }
            )
        } else {
            AlertDialog(
                onDismissRequest = { showBuildDialog = false },
                title = { Text(stringResource(R.string.browser_build_dialog_title_jar)) },
                text = {
                    Text(stringResource(R.string.browser_build_desc_default))
                },
                confirmButton = {
                    TextButton(onClick = {
                        showBuildDialog = false
                        pendingBuildKind = BuildUseCase.BuildKind.JAR
                        treePicker.launch(null)
                    }) { Text(stringResource(R.string.action_continue)) }
                },
                dismissButton = {
                    TextButton(onClick = { showBuildDialog = false }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            )
        }
    }

    if (showDexMarkDialog) {
        DexMarkDialog(state = state, vm = vm, onDismiss = { showDexMarkDialog = false })
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = {
                Text(stringResource(R.string.browser_delete_confirm_title_fmt, state.selectedKeys.size))
            },
            text = { Text(stringResource(R.string.browser_delete_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    vm.deleteSelected { }
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (showScriptPicker) {
        ScriptPickerDialog(
            onDismiss = { showScriptPicker = false },
            onRun = { selected ->
                showScriptPicker = false
                vm.requestRunActions(selected)
            }
        )
    }

    if (tagInput.showing) {
        TagInputDialog(
            scriptName = tagInput.scriptName,
            tag = tagInput.tag,
            validating = tagInput.validating,
            error = tagInput.error,
            onTagChange = vm::updatePendingTag,
            onConfirm = { vm.confirmTagAndRun() },
            onDismiss = { vm.cancelTagInput() }
        )
    }

    if (limitInput.showing) {
        LimitInputDialog(
            limit = limitInput.limit,
            error = limitInput.error,
            onLimitChange = vm::updatePendingLimit,
            onConfirm = { vm.confirmLimitAndRun() },
            onDismiss = { vm.cancelLimitInput() }
        )
    }

    if (warn.showing) {
        ScriptWarnDialog(
            scriptIds = warn.scriptIds,
            remainingSec = warn.remainingSec,
            onConfirm = { vm.confirmWarnAndProceed() },
            onDismiss = { vm.cancelWarn() }
        )
    }

    if (actionState.running) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text(stringResource(R.string.browser_actions_progress_title)) },
            text = {
                Column {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Spacer(Modifier.size(8.dp))
                    Text(actionState.stage, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {}
        )
    }

    if (showImportRootPicker) {
        AlertDialog(
            onDismissRequest = { showImportRootPicker = false },
            title = { Text(stringResource(R.string.browser_import_picker_title)) },
            text = {
                Column {
                    state.roots.forEach { root ->
                        val cleanName = root.displayName
                            .removeSuffix("_dex_decomp")
                            .replaceFirstChar { it.titlecase() }
                        TextButton(
                            onClick = {
                                showImportRootPicker = false
                                importTargetRoot = root.rootDecompDir
                                importPicker.launch(arrayOf("*/*"))
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(cleanName, modifier = Modifier.weight(1f))
                            Icon(Icons.Filled.ChevronRight, contentDescription = null)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showImportRootPicker = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun BrowserToolbar(
    searchExpanded: Boolean,
    onToggleSearch: () -> Unit,
    selectionCount: Int,
    onImport: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
    onActions: () -> Unit,
    actionsRunning: Boolean
) {
    val hasSelection = selectionCount > 0
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onToggleSearch) {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = stringResource(R.string.cd_search),
                    tint = if (searchExpanded) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface
                )
            }
            IconButton(onClick = onImport) {
                Icon(Icons.Filled.FileDownload, contentDescription = stringResource(R.string.cd_import_dex))
            }
            IconButton(onClick = onDelete, enabled = hasSelection) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.cd_delete_selection),
                    tint = if (hasSelection) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.outline
                )
            }
            IconButton(onClick = onExport, enabled = hasSelection) {
                Icon(
                    Icons.Filled.FileUpload,
                    contentDescription = stringResource(R.string.cd_export_merged_dex),
                    tint = if (hasSelection) MaterialTheme.colorScheme.tertiary
                    else MaterialTheme.colorScheme.outline
                )
            }
            IconButton(onClick = onActions, enabled = !actionsRunning) {
                Icon(
                    Icons.Filled.AutoFixHigh,
                    contentDescription = stringResource(R.string.cd_actions),
                    tint = if (actionsRunning) MaterialTheme.colorScheme.outline
                    else MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.weight(1f))
            if (hasSelection) {
                Text(
                    "$selectionCount",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 10.dp)
                )
            }
        }
    }
    HorizontalDivider()
}

@Composable
private fun EmptyBrowser(state: FileBrowserViewModel.UiState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            if (state.query.isNotBlank()) {
                stringResource(R.string.browser_empty_search_fmt, state.query)
            } else {
                stringResource(R.string.browser_empty)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (state.query.isBlank() && state.workDir != null) {
            Spacer(Modifier.size(6.dp))
            Text(
                state.workDir.absolutePath,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline
            )
        }
    }
}

@Composable
private fun SingleTree(
    state: FileBrowserViewModel.UiState,
    vm: FileBrowserViewModel,
    onOpenFile: (String) -> Unit
) {
    val hScroll = rememberScrollState()
    LazyColumn(
        Modifier
            .fillMaxSize()
            .horizontalScroll(hScroll)
    ) {
        items(state.visibleNodes, key = { it.expandKey() }) { node ->
            NodeRow(
                node = node,
                onClick = { handleClick(state, vm, node, onOpenFile) },
                onLongClick = { vm.beginSelection(node) },
                expanded = node.expandKey() in state.expanded,
                selected = node.expandKey() in state.selectedKeys
            )
        }
    }
}

@Composable
private fun SplitTree(
    state: FileBrowserViewModel.UiState,
    vm: FileBrowserViewModel,
    onOpenFile: (String) -> Unit
) {
    val left = state.roots.firstOrNull { it.displayName.startsWith("framework") }
    val right = state.roots.firstOrNull { it.displayName.startsWith("services") }
    val r1 = left ?: state.roots.getOrNull(0)
    val r2 = right ?: state.roots.getOrNull(1)

    Row(Modifier.fillMaxSize()) {
        SplitColumn(
            title = r1?.displayName?.removeSuffix("_dex_decomp")
                ?.replaceFirstChar { it.titlecase() }
                ?: stringResource(R.string.browser_split_pane_left),
            root = r1, state = state, vm = vm, onOpenFile = onOpenFile,
            modifier = Modifier.weight(1f).fillMaxHeight()
        )
        VerticalDivider(Modifier.fillMaxHeight())
        SplitColumn(
            title = r2?.displayName?.removeSuffix("_dex_decomp")
                ?.replaceFirstChar { it.titlecase() }
                ?: stringResource(R.string.browser_split_pane_right),
            root = r2, state = state, vm = vm, onOpenFile = onOpenFile,
            modifier = Modifier.weight(1f).fillMaxHeight()
        )
    }
}

@Composable
private fun SplitColumn(
    title: String,
    root: FileBrowserViewModel.Node?,
    state: FileBrowserViewModel.UiState,
    vm: FileBrowserViewModel,
    onOpenFile: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val rootPath = root?.rootDecompDir?.absolutePath
    val nodesForPane = remember(state.visibleNodes, rootPath) {
        if (rootPath == null) emptyList()
        else state.visibleNodes.filter { it.rootDecompDir.absolutePath == rootPath }
    }
    Column(modifier) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            )
        }
        HorizontalDivider()
        if (nodesForPane.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.browser_pane_empty),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            val hScroll = rememberScrollState()
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .horizontalScroll(hScroll)
            ) {
                items(nodesForPane, key = { it.expandKey() }) { node ->
                    NodeRow(
                        node = node,
                        onClick = { handleClick(state, vm, node, onOpenFile) },
                        onLongClick = { vm.beginSelection(node) },
                        expanded = node.expandKey() in state.expanded,
                        selected = node.expandKey() in state.selectedKeys
                    )
                }
            }
        }
    }
}

private fun handleClick(
    state: FileBrowserViewModel.UiState,
    vm: FileBrowserViewModel,
    node: FileBrowserViewModel.Node,
    onOpenFile: (String) -> Unit
) {
    if (state.selectionMode) {
        vm.toggleSelect(node)
    } else if (node.isDir) {
        vm.toggleExpand(node)
    } else {
        onOpenFile(node.primaryFile.absolutePath)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NodeRow(
    node: FileBrowserViewModel.Node,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    expanded: Boolean,
    selected: Boolean
) {
    val rotation by animateFloatAsState(if (expanded) 90f else 0f, label = "chevron")
    val indent = (node.depth * 12).dp
    val bgColor =
        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
        else androidx.compose.ui.graphics.Color.Transparent

    Row(
        modifier = Modifier
            .height(22.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .background(bgColor)
            .padding(horizontal = 4.dp, vertical = 0.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(Modifier.size(indent))

        if (node.isDir) {
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = null,
                modifier = Modifier
                    .size(14.dp)
                    .rotate(rotation)
            )
            Spacer(Modifier.size(2.dp))
            Icon(
                if (expanded) Icons.Filled.FolderOpen else Icons.Filled.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(14.dp)
            )
        } else {
            Spacer(Modifier.size(16.dp))
            val isSmali = node.displayName.endsWith(".smali", ignoreCase = true)
            Icon(
                if (isSmali) Icons.Filled.Code else Icons.AutoMirrored.Filled.InsertDriveFile,
                contentDescription = null,
                tint = if (isSmali) MaterialTheme.colorScheme.tertiary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp)
            )
        }
        Spacer(Modifier.size(5.dp))
        Text(
            node.displayName,
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = TextUnit(12.5f, TextUnitType.Sp),
                lineHeight = TextUnit(14f, TextUnitType.Sp)
            ),
            fontWeight = if (node.depth == 0 || selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            softWrap = false
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun DexMarkDialog(
    state: FileBrowserViewModel.UiState,
    vm: FileBrowserViewModel,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.browser_dex_mark_title)) },
        text = {
            Column(Modifier.heightIn(min = 200.dp, max = 480.dp)) {
                Text(
                    stringResource(R.string.browser_dex_mark_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.size(8.dp))
                LazyColumn(Modifier.fillMaxSize()) {
                    state.roots.forEach { root ->
                        item {
                            Text(
                                root.displayName,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(vertical = 6.dp)
                            )
                        }
                        items(
                            vm.dexInfoFor(root.rootDecompDir),
                            key = { "${root.expandKey()}|${it.name}" }
                        ) { info ->
                            val value = info.entry.value
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .combinedClickable(
                                        onClick = {
                                            vm.setEditedValue(
                                                root.rootDecompDir, info.name,
                                                if (value == 1) 0 else 1
                                            )
                                        },
                                        onLongClick = {}
                                    )
                                    .padding(vertical = 4.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = value == 1,
                                    onCheckedChange = { checked ->
                                        vm.setEditedValue(
                                            root.rootDecompDir, info.name,
                                            if (checked) 1 else 0
                                        )
                                    }
                                )
                                Spacer(Modifier.size(4.dp))
                                Text(info.name, fontWeight = FontWeight.Medium)
                                Spacer(Modifier.weight(1f))
                                Text(
                                    stringResource(R.string.browser_dex_version_fmt, info.entry.version),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_done)) }
        }
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ScriptPickerDialog(
    onDismiss: () -> Unit,
    onRun: (List<ActionsRegistry.Entry>) -> Unit
) {
    val all = remember { ActionsRegistry.all }
    val selected = remember {
        androidx.compose.runtime.mutableStateMapOf<String, Boolean>().apply {
            all.forEach { put(it.script.id, false) }
        }
    }
    // Mutual exclusion: TOOLBOX trong group TOOLBOX, UPDATE trong group UPDATE.
    // Ngoài ra TOOLBOX và UPDATE loại trừ lẫn nhau (cả 2 cùng import dex sẽ xung đột).
    fun toggle(entry: ActionsRegistry.Entry) {
        val id = entry.script.id
        val newVal = !(selected[id] == true)
        if (newVal) {
            when (entry.group) {
                ScriptGroup.TOOLBOX -> {
                    all.filter { it.group == ScriptGroup.TOOLBOX || it.group == ScriptGroup.UPDATE }
                        .forEach { selected[it.script.id] = false }
                }
                ScriptGroup.UPDATE -> {
                    all.filter { it.group == ScriptGroup.TOOLBOX || it.group == ScriptGroup.UPDATE }
                        .forEach { selected[it.script.id] = false }
                }
                else -> Unit
            }
        }
        selected[id] = newVal
    }

    val chosen = all.filter { selected[it.script.id] == true }
    // Cho phép script có operations rỗng nhưng chạy được toàn bộ trong afterCommit:
    // - DexRedivisionScript: redivide classes ở tầng folder.
    // - UpdateKaoriosDexScript: xóa folder kaorios/kousei + import dex mới.
    val zeroOpAllowed = setOf(DexRedivisionScript.id, UpdateKaoriosDexScript.id)
    val hasEmpty = chosen.any { it.script.operations.isEmpty() && it.script.id !in zeroOpAllowed }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.browser_script_picker_title)) },
        text = {
            Column(Modifier.heightIn(min = 200.dp, max = 520.dp)) {
                Text(
                    stringResource(R.string.browser_script_picker_intro),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.size(8.dp))
                LazyColumn(Modifier.fillMaxSize()) {
                    items(all, key = { it.script.id }) { entry ->
                        val checked = selected[entry.script.id] == true
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(onClick = { toggle(entry) }, onLongClick = {})
                                .padding(vertical = 6.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { toggle(entry) }
                            )
                            Spacer(Modifier.size(4.dp))
                            Column(Modifier.weight(1f)) {
                                Text(entry.script.displayName, fontWeight = FontWeight.Medium)
                                val tagPrefix = when {
                                    entry.script.id == RemoveHooksScript.id ->
                                        stringResource(R.string.browser_script_tag_remove)
                                    entry.group == ScriptGroup.TOOLBOX ->
                                        stringResource(R.string.browser_script_group_toolbox)
                                    entry.group == ScriptGroup.UPDATE ->
                                        stringResource(R.string.browser_script_group_update)
                                    entry.group == ScriptGroup.REBUILD ->
                                        stringResource(R.string.browser_script_tag_rebuild)
                                    else -> ""
                                }
                                val opSuffix = if (entry.isExperimental) {
                                        stringResource(
                                            R.string.browser_script_experimental_fmt,
                                            entry.script.operations.size
                                        )
                                } else {
                                    stringResource(R.string.browser_script_op_count_fmt, entry.script.operations.size)
                                }
                                Text(
                                    tagPrefix + opSuffix,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
                if (hasEmpty) {
                    Spacer(Modifier.size(8.dp))
                    Text(
                        stringResource(R.string.browser_script_experimental_warn),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onRun(chosen) },
                enabled = chosen.isNotEmpty() && !hasEmpty
            ) { Text(stringResource(R.string.browser_script_run_fmt, chosen.size)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

@Composable
private fun TagInputDialog(
    scriptName: String,
    tag: String,
    validating: Boolean,
    error: String?,
    onTagChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!validating) onDismiss() },
        title = {
            Text(
                if (scriptName.isBlank()) {
                    stringResource(R.string.browser_tag_dialog_title)
                } else {
                    stringResource(R.string.browser_tag_title_fmt, scriptName)
                }
            )
        },
        text = {
            Column {
                Text(
                    stringResource(R.string.browser_tag_dialog_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.size(4.dp))
                Text(
                    stringResource(R.string.browser_tag_dialog_extended_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.size(10.dp))
                OutlinedTextField(
                    value = tag,
                    onValueChange = onTagChange,
                    singleLine = true,
                    enabled = !validating,
                    placeholder = { Text(stringResource(R.string.browser_tag_dialog_placeholder)) },
                    isError = error != null,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.size(6.dp))
                if (validating) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LinearProgressIndicator(Modifier.weight(1f).height(4.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.browser_tag_dialog_validating), style = MaterialTheme.typography.labelSmall)
                    }
                } else if (error != null) {
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    Text(
                        stringResource(R.string.browser_tag_dialog_hint_ok),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !validating && tag.isNotBlank()) {
                Text(stringResource(R.string.browser_tag_dialog_accept))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !validating) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
private fun LimitInputDialog(
    limit: String,
    error: String?,
    onLimitChange: (String) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.picker_limit_dialog_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.picker_limit_dialog_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.size(10.dp))
                OutlinedTextField(
                    value = limit,
                    onValueChange = onLimitChange,
                    singleLine = true,
                    label = { Text(stringResource(R.string.picker_limit_label)) },
                    placeholder = { Text("64000") },
                    supportingText = {
                        Text(
                            stringResource(R.string.picker_limit_dialog_hint),
                            style = MaterialTheme.typography.labelSmall
                        )
                    },
                    isError = error != null,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                if (error != null) {
                    Spacer(Modifier.size(4.dp))
                    Text(
                        error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = error == null && limit.isNotBlank()) {
                Text(stringResource(R.string.action_continue))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
private fun ScriptWarnDialog(
    scriptIds: List<String>,
    remainingSec: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(R.string.browser_script_warn_title),
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.SemiBold
            )
        },
        text = {
            Column {
                scriptIds.forEach { id ->
                    val msg = when (id) {
                        ActionState.SCRIPT_ID_UPDATE_KAORIOS_DEX ->
                            stringResource(R.string.browser_script_warn_update_kaorios_dex)
                        DisableSecureFlagScript.id ->
                            stringResource(R.string.browser_script_warn_disable_secure_flag)
                        else -> id
                    }
                    Text(
                        msg,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = remainingSec <= 0) {
                Text(
                    if (remainingSec > 0) {
                        stringResource(R.string.browser_script_warn_continue_countdown_fmt, remainingSec)
                    } else {
                        stringResource(R.string.browser_script_warn_continue)
                    },
                    color = if (remainingSec <= 0) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.outline
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}
