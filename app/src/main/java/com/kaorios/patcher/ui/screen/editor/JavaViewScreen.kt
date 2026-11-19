package com.kaorios.patcher.ui.screen.editor

import android.graphics.Typeface
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.kaorios.patcher.R
import com.kaorios.patcher.data.log.LogSink
import com.kaorios.patcher.data.workspace.DexEditedStore
import com.kaorios.patcher.editor.JavaViewRequest
import com.kaorios.patcher.editor.Smali2Java
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.widget.CodeEditor
import io.github.rosemoe.sora.widget.EditorSearcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun JavaViewScreen(filePath: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var content by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val initialSubtitle = stringResource(R.string.java_view_subtitle_translating)
    var subtitle by remember { mutableStateOf(initialSubtitle) }
    var showSearchBar by remember { mutableStateOf(false) }

    // Editor instance holder để trigger search từ bên ngoài.
    val editorRef = remember { EditorHolder() }

    LaunchedEffect(filePath) {
        scope.launch {
            runCatching {
                withContext(Dispatchers.IO) {
                    val payload = JavaViewRequest.consume()
                    val files = payload?.files?.takeIf { it.isNotEmpty() }
                        ?: listOf(File(filePath))
                    val primary = payload?.primary ?: File(filePath)
                    val version = payload?.dexVersion ?: resolveDexVersion(primary)
                    val hint = deriveHint(primary)
                    val smaliTexts = files.map { it.readText() }
                    LogSink.i("JavaView", "Translating ${files.size} file(s), primary=${primary.name}, hint=$hint")
                    Smali2Java.translate(smaliTexts, version, hint)
                }
            }.onSuccess { r ->
                content = r.primaryCode
                subtitle = "${r.primaryType} • ${r.classCount} class"
            }.onFailure { t ->
                LogSink.e("JavaView", "decompile fail", t)
                error = t.message ?: t.toString()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            File(filePath).nameWithoutExtension,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showSearchBar = !showSearchBar },
                        enabled = content != null
                    ) {
                        Icon(
                            Icons.Filled.Search,
                            contentDescription = stringResource(R.string.cd_search),
                            tint = if (showSearchBar) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            )
        }
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            when {
                content != null -> {
                    JavaCodeView(
                        code = content!!,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        holder = editorRef
                    )
                    if (showSearchBar) {
                        HorizontalDivider()
                        JavaSearchBar(
                            onSearch = { q ->
                                if (q.isNotEmpty()) editorRef.editor?.searcher?.search(
                                    q, EditorSearcher.SearchOptions(true, true)
                                )
                            },
                            onPrev = { editorRef.editor?.searcher?.gotoPrevious() },
                            onNext = { editorRef.editor?.searcher?.gotoNext() },
                            onClose = { showSearchBar = false }
                        )
                    }
                }
                error != null -> Column(
                    Modifier.fillMaxSize().padding(16.dp)
                ) {
                    Text(
                        stringResource(R.string.java_view_fail_title),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(
                        error!!,
                        modifier = Modifier.padding(top = 8.dp),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        stringResource(R.string.java_view_see_log),
                        modifier = Modifier.padding(top = 12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                else -> Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.java_view_loading), Modifier.padding(top = 12.dp))
                }
            }
        }
    }
}

private class EditorHolder(var editor: CodeEditor? = null)

private fun resolveDexVersion(smaliFile: File): Int {
    var cur: File? = smaliFile.parentFile
    while (cur != null) {
        val parent = cur.parentFile ?: break
        if (parent.name.endsWith("_dex_decomp")) {
            val map = DexEditedStore.read(parent)
            return map[cur.name]?.version ?: 39
        }
        cur = parent
    }
    return 39
}

private fun deriveHint(file: File): String {
    // Từ path: .../classesN/a/b/C.smali → LA/b/C;
    val parts = mutableListOf<String>()
    var cur: File? = file
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
    if (parts.isEmpty()) return ""
    return "L" + parts.reversed().joinToString("/") + ";"
}

@Composable
private fun JavaCodeView(
    code: String,
    modifier: Modifier = Modifier,
    holder: EditorHolder
) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            CodeEditor(ctx).apply {
                typefaceText = Typeface.MONOSPACE
                setTextSize(13.5f)
                colorScheme = TextMateColorScheme.create(ThemeRegistry.getInstance())
                isEditable = false
                setText(code)
                isLineNumberEnabled = true
                isWordwrap = false
            }.also { holder.editor = it }
        },
        update = { editor ->
            if (editor.text.toString() != code) editor.setText(code)
        }
    )
    DisposableEffect(Unit) {
        onDispose {
            holder.editor?.release()
            holder.editor = null
        }
    }
}

@Composable
private fun JavaSearchBar(
    onSearch: (String) -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    onSearch(it)
                },
                placeholder = { Text(stringResource(R.string.java_view_search_placeholder), style = MaterialTheme.typography.bodySmall) },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
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
    }
    Spacer(Modifier.size(0.dp))
}
