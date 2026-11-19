package com.kaorios.patcher.ui.screen.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.FindReplace
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import com.kaorios.patcher.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.kaorios.patcher.editor.JavaViewRequest
import com.kaorios.patcher.editor.SmaliInstructionRepo
import com.kaorios.patcher.editor.SmaliParse
import com.kaorios.patcher.editor.SoraSmaliEditor
import com.kaorios.patcher.editor.rememberSoraEditorState
import java.io.File
import io.github.rosemoe.sora.text.Content
import io.github.rosemoe.sora.widget.EditorSearcher

private val SYMBOLS = listOf(
    "->", "{", "}", "(", ")", ",", ".", ";", "\"", "?", "+", "-", "*", "/", "<", ">", "[", "]", ":"
)

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SmaliEditorScreen(
    workDir: String,
    filePath: String,
    onBack: () -> Unit,
    onOpenJavaView: (String) -> Unit,
    onOpenSettings: () -> Unit,
    vm: SmaliEditorViewModel = viewModel()
) {
    val ctx = LocalContext.current
    val state by vm.state.collectAsStateWithLifecycle()
    val editorState = rememberSoraEditorState()
    val snackbar = remember { SnackbarHostState() }

    var showSearchBar by remember { mutableStateOf(false) }
    var showJumpTo by remember { mutableStateOf(false) }
    var showInstructions by remember { mutableStateOf(false) }
    var showActions by remember { mutableStateOf(false) }
    var showMethodList by remember { mutableStateOf(false) }
    var showStringsList by remember { mutableStateOf(false) }
    var referenceDialog by remember { mutableStateOf<ReferenceResult?>(null) }
    var showDirtyConfirm by remember { mutableStateOf(false) }
    var showCopyDialog by remember { mutableStateOf(false) }
    var showJavaViewPicker by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    val requestClose = {
        if (state.dirty) showDirtyConfirm = true else onBack()
    }

    BackHandler(enabled = true) { requestClose() }

    LaunchedEffect(filePath) { vm.load(workDir, filePath) }

    LaunchedEffect(state.info) {
        state.info?.let {
            snackbar.showSnackbar(it)
            vm.consumeInfo()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    val displayName = state.fileName.removeSuffix(".smali").removeSuffix(".SMALI")
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .combinedClickableForTitle { showCopyDialog = true }
                    ) {
                        Text(displayName, style = MaterialTheme.typography.titleMedium)
                        if (state.dirty) {
                            Spacer(Modifier.size(6.dp))
                            Text(
                                "●",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { requestClose() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = { editorState.editor?.undo() }) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
                    }
                    IconButton(onClick = { editorState.editor?.redo() }) {
                        Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo")
                    }
                    IconButton(onClick = { showSearchBar = !showSearchBar }) {
                        Icon(
                            Icons.Filled.Search,
                            contentDescription = "Search",
                            tint = if (showSearchBar) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    IconButton(onClick = { vm.save() }) {
                        BadgedBox(badge = { if (state.dirty) Badge() }) {
                            Icon(Icons.Filled.Save, contentDescription = "Save")
                        }
                    }
                    Box {
                        IconButton(onClick = { showActions = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More")
                        }
                        DropdownMenu(
                            expanded = showActions,
                            onDismissRequest = { showActions = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.editor_menu_method_list)) },
                                leadingIcon = {
                                    Icon(Icons.AutoMirrored.Filled.FormatListBulleted, null)
                                },
                                onClick = { showActions = false; showMethodList = true }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.editor_menu_string_list)) },
                                leadingIcon = { Icon(Icons.Filled.FormatQuote, null) },
                                onClick = { showActions = false; showStringsList = true }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.editor_menu_jump_to_line)) },
                                onClick = { showActions = false; showJumpTo = true }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.editor_menu_jump_reference)) },
                                leadingIcon = { Icon(Icons.Filled.DataObject, null) },
                                onClick = {
                                    showActions = false
                                    referenceDialog = resolveReferenceAtCursor(editorState, vm)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.editor_menu_smali_instructions)) },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.HelpOutline, null) },
                                onClick = { showActions = false; showInstructions = true }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.editor_menu_translate_java)) },
                                leadingIcon = { Icon(Icons.Filled.DataObject, null) },
                                onClick = {
                                    showActions = false
                                    showJavaViewPicker = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.editor_menu_settings)) },
                                leadingIcon = { Icon(Icons.Filled.Settings, null) },
                                onClick = { showActions = false; onOpenSettings() }
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
            SoraSmaliEditor(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                state = editorState,
                text = state.content,
                prefs = state.prefs,
                onTextChanged = vm::onContentChanged
            )
            HorizontalDivider()
            if (showSearchBar) {
                SearchBar(
                    onSearch = { q ->
                        if (q.isNotBlank()) editorState.editor?.searcher?.search(
                            q, EditorSearcher.SearchOptions(true, true)
                        )
                    },
                    onNext = { editorState.editor?.searcher?.gotoNext() },
                    onPrev = { editorState.editor?.searcher?.gotoPrevious() },
                    onReplace = { r -> editorState.editor?.searcher?.replaceThis(r) },
                    onReplaceAll = { r -> editorState.editor?.searcher?.replaceAll(r) },
                    onClose = { showSearchBar = false }
                )
                HorizontalDivider()
            }
            SymbolBar(onInsert = { sym -> editorState.editor?.insertText(sym, 0) })
        }
    }

    if (showJavaViewPicker) {
        ClassSiblingPickerDialog(
            primaryFilePath = state.filePath,
            onDismiss = { showJavaViewPicker = false },
            onConfirm = { selected ->
                showJavaViewPicker = false
                val primary = File(state.filePath)
                JavaViewRequest.pending = JavaViewRequest.Payload(
                    files = selected,
                    primary = primary,
                    dexVersion = state.dexVersion
                )
                onOpenJavaView(state.filePath)
            }
        )
    }

    if (showCopyDialog) {
        CopyFileNameDialog(
            fileName = state.fileName,
            descriptor = deriveTypeDescriptor(state.filePath, state.content),
            onCopy = { text ->
                clipboard.setText(AnnotatedString(text))
                showCopyDialog = false
            },
            onDismiss = { showCopyDialog = false }
        )
    }

    if (showJumpTo) {
        JumpToLineDialog(
            onDismiss = { showJumpTo = false },
            onGo = { line -> jumpToLine(editorState, line) }
        )
    }

    if (showInstructions) {
        SmaliInstructionsDialog(
            onDismiss = { showInstructions = false },
            onPick = { name ->
                editorState.editor?.insertText(name, 0)
                showInstructions = false
            }
        )
    }

    if (showMethodList) {
        MethodListDialog(
            methods = state.methods,
            onDismiss = { showMethodList = false },
            onJump = { line ->
                jumpToLine(editorState, line + 1); showMethodList = false
            }
        )
    }

    if (showStringsList) {
        StringListDialog(
            strings = state.strings,
            onDismiss = { showStringsList = false },
            onJump = { line ->
                jumpToLine(editorState, line + 1); showStringsList = false
            }
        )
    }

    referenceDialog?.let { ref ->
        ReferenceDialog(
            ref = ref,
            onDismiss = { referenceDialog = null },
            onOpen = { path ->
                referenceDialog = null
            }
        )
    }

    if (showDirtyConfirm) {
        AlertDialog(
            onDismissRequest = { showDirtyConfirm = false },
            title = { Text(stringResource(R.string.editor_dirty_confirm_title)) },
            text = { Text(stringResource(R.string.editor_dirty_confirm_body_fmt, state.fileName)) },
            confirmButton = {
                TextButton(onClick = {
                    showDirtyConfirm = false
                    vm.save()
                    onBack()
                }) { Text(stringResource(R.string.editor_dirty_save_exit)) }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        showDirtyConfirm = false
                        onBack()
                    }) { Text(stringResource(R.string.editor_dirty_discard)) }
                    TextButton(onClick = { showDirtyConfirm = false }) {
                        Text(stringResource(R.string.action_stay))
                    }
                }
            }
        )
    }
}

private fun jumpToLine(state: com.kaorios.patcher.editor.SoraEditorState, oneBased: Int) {
    val ed = state.editor ?: return
    val content: Content = ed.text
    val safe = oneBased.coerceIn(1, content.lineCount)
    ed.setSelection(safe - 1, 0)
    ed.ensureSelectionVisible()
}

private data class ReferenceResult(
    val line: String,
    val methodRef: SmaliParse.MethodInvokeRef?,
    val fieldRef: SmaliParse.FieldAccessRef?,
    val resolvedPath: String?
)

private fun resolveReferenceAtCursor(
    state: com.kaorios.patcher.editor.SoraEditorState,
    vm: SmaliEditorViewModel
): ReferenceResult? {
    val editor = state.editor ?: return null
    val cursor = editor.cursor
    val line = cursor.leftLine
    val lineText = editor.text.getLineString(line)
    val methodRef = SmaliParse.parseInvoke(lineText)
    val fieldRef = SmaliParse.parseFieldAccess(lineText)
    val ownerType = methodRef?.ownerType ?: fieldRef?.ownerType
    val resolvedFile = ownerType?.let { vm.resolveTypeToFile(it) }
    return ReferenceResult(
        line = lineText.trim(),
        methodRef = methodRef,
        fieldRef = fieldRef,
        resolvedPath = resolvedFile?.absolutePath
    )
}

@Composable
private fun ReferenceDialog(
    ref: ReferenceResult,
    onDismiss: () -> Unit,
    onOpen: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_reference_title)) },
        text = {
            Column {
                Text(
                    ref.line,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.size(8.dp))
                when {
                    ref.methodRef != null -> {
                        Text(stringResource(R.string.editor_reference_method_invoke), fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.editor_ref_owner_line, ref.methodRef.ownerType))
                        Text(stringResource(R.string.editor_ref_name_line, ref.methodRef.name))
                        Text(stringResource(R.string.editor_ref_desc_line, ref.methodRef.descriptor))
                    }
                    ref.fieldRef != null -> {
                        Text(stringResource(R.string.editor_reference_field_access), fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.editor_ref_owner_line, ref.fieldRef.ownerType))
                        Text(stringResource(R.string.editor_ref_name_line, ref.fieldRef.name))
                        Text(stringResource(R.string.editor_ref_type_line, ref.fieldRef.type))
                    }
                    else -> Text(stringResource(R.string.editor_reference_not_found))
                }
                Spacer(Modifier.size(8.dp))
                if (ref.resolvedPath != null) {
                    Text(
                        stringResource(R.string.editor_reference_resolved_fmt, ref.resolvedPath!!),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else if (ref.methodRef != null || ref.fieldRef != null) {
                    Text(
                        stringResource(R.string.editor_reference_unresolved),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        }
    )
}

@Composable
private fun SymbolBar(onInsert: (String) -> Unit) {
    val scroll = rememberScrollState()
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .horizontalScroll(scroll)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            SYMBOLS.forEach { sym ->
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(8.dp),
                    onClick = { onInsert(sym) }
                ) {
                    Box(
                        modifier = Modifier
                            .heightIn(min = 36.dp)
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            sym,
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }
    }
}

/**
 * Thanh tìm & thay thế inline, đặt ngay trên SymbolBar ở đáy màn hình.
 * 2 dòng:
 *   [Find____________________] [▲] [▼] [×]
 *   [Replace_________________] [Thay] [Thay tất cả]
 *
 * Không gọi [EditorSearcher.search] trực tiếp trong onValueChange — mỗi lần gõ sẽ khiến
 * CodeEditor giật focus về view Android, ô Compose không nhận phím. Tìm kiếm được debounce.
 */
@Composable
private fun SearchBar(
    onSearch: (String) -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onReplace: (String) -> Unit,
    onReplaceAll: (String) -> Unit,
    onClose: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    val findFocus = remember { FocusRequester() }

    // Đưa focus vào ô Find sau khi CodeEditor nhả focus (tránh xung đột AndroidView / Compose).
    LaunchedEffect(Unit) {
        delay(60)
        findFocus.requestFocus()
    }

    LaunchedEffect(query) {
        delay(220)
        onSearch(query)
    }

    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 4.dp)
        ) {
            // Dòng 1: Find + Prev/Next/Close
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.editor_search_find), style = MaterialTheme.typography.bodySmall) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            onSearch(query)
                        }
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(findFocus)
                )
                IconButton(onClick = onPrev, enabled = query.isNotEmpty()) {
                    Icon(Icons.Filled.ArrowUpward, contentDescription = "Prev")
                }
                IconButton(onClick = onNext, enabled = query.isNotEmpty()) {
                    Icon(Icons.Filled.ArrowDownward, contentDescription = "Next")
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_close))
                }
            }
            // Dòng 2: Replace + actions
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = replacement,
                    onValueChange = { replacement = it },
                    placeholder = { Text(stringResource(R.string.editor_search_replace), style = MaterialTheme.typography.bodySmall) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = { onReplace(replacement) },
                    enabled = query.isNotEmpty()
                ) { Icon(Icons.Filled.SwapHoriz, contentDescription = "Thay 1") }
                IconButton(
                    onClick = { onReplaceAll(replacement) },
                    enabled = query.isNotEmpty()
                ) { Icon(Icons.Filled.FindReplace, contentDescription = stringResource(R.string.cd_replace_all)) }
            }
        }
    }
}

@Composable
private fun CopyFileNameDialog(
    fileName: String,
    descriptor: String,
    onCopy: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val baseName = fileName.removeSuffix(".smali").removeSuffix(".SMALI")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Copy") },
        text = {
            Column {
                Surface(
                    onClick = { onCopy(baseName) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(stringResource(R.string.editor_copy_filename_label), style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(baseName, fontFamily = FontFamily.Monospace)
                    }
                }
                Spacer(Modifier.size(8.dp))
                Surface(
                    onClick = { onCopy(descriptor) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(stringResource(R.string.editor_copy_descriptor_label),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(descriptor, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } }
    )
}

@Composable
private fun JumpToLineDialog(onDismiss: () -> Unit, onGo: (Int) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_jump_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { if (it.all(Char::isDigit)) text = it },
                label = { Text(stringResource(R.string.editor_jump_line_label)) },
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(onClick = {
                val n = text.toIntOrNull() ?: 1
                onGo(n); onDismiss()
            }) { Text(stringResource(R.string.editor_jump_go)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } }
    )
}

@Composable
private fun SmaliInstructionsDialog(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val ctx = LocalContext.current
    val allItems = remember { SmaliInstructionRepo.load(ctx) }
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, allItems) {
        if (query.isBlank()) allItems
        else allItems.filter { it.contains(query, ignoreCase = true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Smali instructions (${filtered.size})") },
        text = {
            Column(Modifier.heightIn(min = 200.dp, max = 420.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.editor_instructions_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.size(8.dp))
                LazyColumn {
                    items(filtered) { item ->
                        Surface(
                            onClick = { onPick(item) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                item,
                                modifier = Modifier.padding(10.dp),
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } }
    )
}

@Composable
private fun MethodListDialog(
    methods: List<SmaliParse.MethodEntry>,
    onDismiss: () -> Unit,
    onJump: (Int) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, methods) {
        if (query.isBlank()) methods else methods.filter { it.signature.contains(query, true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Methods (${filtered.size})") },
        text = {
            Column(Modifier.heightIn(min = 200.dp, max = 480.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.editor_method_list_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.size(8.dp))
                LazyColumn {
                    items(filtered) { m ->
                        Surface(
                            onClick = { onJump(m.line) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(8.dp)) {
                                Row {
                                    Text(
                                        "L${m.line + 1}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(Modifier.size(8.dp))
                                    Text(
                                        m.modifiers,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.tertiary
                                    )
                                }
                                Text(
                                    "${m.name} ${m.descriptor}",
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } }
    )
}

@Composable
private fun StringListDialog(
    strings: List<SmaliParse.StringEntry>,
    onDismiss: () -> Unit,
    onJump: (Int) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, strings) {
        if (query.isBlank()) strings else strings.filter { it.value.contains(query, true) }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Strings (${filtered.size})") },
        text = {
            Column(Modifier.heightIn(min = 200.dp, max = 480.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.editor_string_list_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.size(8.dp))
                LazyColumn {
                    items(filtered) { s ->
                        Surface(
                            onClick = { onJump(s.line) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.padding(8.dp)) {
                                Text(
                                    "L${s.line + 1}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    "\"${s.value}\"",
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 3
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } }
    )
}

/** Modifier clickable cho title trong TopAppBar (chỉ dùng onClick). */
@Composable
private fun Modifier.combinedClickableForTitle(onClick: () -> Unit): Modifier =
    this.then(Modifier.clickable(onClick = onClick))

/**
 * Trích descriptor dạng `Lcom/foo/Bar;` từ smali.
 * Ưu tiên đọc directive `.class` trong content. Fallback dùng path relative từ classesN/.
 */
private fun deriveTypeDescriptor(filePath: String, content: String): String {
    val classLine = content.lineSequence().firstOrNull { it.trimStart().startsWith(".class") }
    if (classLine != null) {
        val match = Regex("""L[\w$/]+;""").find(classLine)
        if (match != null) return match.value
    }
    val f = java.io.File(filePath)
    val parts = mutableListOf<String>()
    var cur: java.io.File? = f
    while (cur != null) {
        val parent = cur.parentFile
        if (parent == null || parent.name.startsWith("classes", ignoreCase = true)) {
            val leaf = cur.name.removeSuffix(".smali").removeSuffix(".SMALI")
            parts.add(leaf)
            break
        }
        parts.add(cur.name)
        cur = parent
    }
    if (parts.isEmpty()) return "L?;"
    val internal = parts.reversed().joinToString("/")
    return "L$internal;"
}

/**
 * Dialog liệt kê các class anh chị (siblings) trong cùng thư mục có tên bắt đầu bằng base name
 * của file hiện tại. Ví dụ file `A.smali` → tự detect `A$1.smali`, `A$2.smali`, `A$Builder.smali`.
 * User chọn tập file cần compile cùng để jadx decompile đủ inner/anonymous class.
 */
@Composable
private fun ClassSiblingPickerDialog(
    primaryFilePath: String,
    onDismiss: () -> Unit,
    onConfirm: (List<File>) -> Unit
) {
    val primaryFile = remember(primaryFilePath) { File(primaryFilePath) }
    val siblings: List<File> = remember(primaryFilePath) {
        val parent = primaryFile.parentFile ?: return@remember listOf(primaryFile)
        val baseName = primaryFile.name.removeSuffix(".smali").removeSuffix(".SMALI")
        val matched = parent.listFiles { f ->
            f.isFile && f.name.endsWith(".smali", ignoreCase = true) &&
                (f.name == primaryFile.name ||
                    f.name.startsWith("$baseName$", ignoreCase = false))
        } ?: emptyArray()
        // Primary lên đầu, còn lại sort theo tên
        val primaryFirst = listOf(primaryFile) + matched.filter { it != primaryFile }
            .sortedBy { it.name }
        primaryFirst.distinctBy { it.absolutePath }
    }
    val selected = remember {
        androidx.compose.runtime.mutableStateMapOf<String, Boolean>().apply {
            siblings.forEach { put(it.absolutePath, true) }
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.editor_class_sibling_title)) },
        text = {
            Column(Modifier.heightIn(min = 180.dp, max = 480.dp)) {
                if (siblings.size == 1) {
                    Text(
                        stringResource(R.string.editor_class_sibling_single_fmt, primaryFile.name),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        stringResource(R.string.editor_class_sibling_multi_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.size(8.dp))
                LazyColumn {
                    items(siblings, key = { it.absolutePath }) { f ->
                        val isPrimary = f == primaryFile
                        val checked = selected[f.absolutePath] == true
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (!isPrimary) selected[f.absolutePath] = !checked
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            androidx.compose.material3.Checkbox(
                                checked = checked,
                                onCheckedChange = { v ->
                                    if (!isPrimary) selected[f.absolutePath] = v
                                },
                                enabled = !isPrimary
                            )
                            Spacer(Modifier.size(4.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    f.name.removeSuffix(".smali").removeSuffix(".SMALI"),
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = if (isPrimary) FontWeight.SemiBold
                                    else FontWeight.Normal
                                )
                                if (isPrimary) {
                                    Text(
                                        "primary (bắt buộc)",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            Text(
                                "${f.length()}B",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val chosen = siblings.filter { selected[it.absolutePath] == true }
                    .ifEmpty { listOf(primaryFile) }
                onConfirm(chosen)
            }) { Text(stringResource(R.string.editor_class_sibling_confirm_fmt, selected.count { it.value })) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}
