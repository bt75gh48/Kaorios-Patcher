package com.kaorios.patcher.ui.screen.editor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kaorios.patcher.R
import com.kaorios.patcher.data.log.LogSink
import com.kaorios.patcher.data.settings.EditorPrefs
import com.kaorios.patcher.data.workspace.DexEditedStore
import com.kaorios.patcher.editor.SmaliParse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class SmaliEditorViewModel(app: Application) : AndroidViewModel(app) {

    data class UiState(
        val filePath: String = "",
        val fileName: String = "",
        val workDir: String = "",
        val dexDirName: String? = null,
        val rootDecompDir: String? = null,
        val dexVersion: Int = 39,
        val loaded: Boolean = false,
        val content: String = "",
        val originalContent: String = "",
        val dirty: Boolean = false,
        val info: String? = null,
        val prefs: EditorPrefs.Snapshot = EditorPrefs.Snapshot(),
        val methods: List<SmaliParse.MethodEntry> = emptyList(),
        val strings: List<SmaliParse.StringEntry> = emptyList()
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            EditorPrefs.flow(getApplication()).collect { prefs ->
                _state.value = _state.value.copy(prefs = prefs)
            }
        }
    }

    fun load(workDirPath: String, filePath: String) = viewModelScope.launch {
        val file = File(filePath)
        val text = withContext(Dispatchers.IO) {
            runCatching { file.readText() }.getOrElse {
                LogSink.e("Editor", "Cannot read ${file.absolutePath}", it)
                ""
            }
        }
        val (root, dexDir) = resolveDexContext(File(workDirPath), file)
        val version = if (root != null && dexDir != null) {
            DexEditedStore.read(root)[dexDir]?.version ?: 39
        } else 39

        val methods = SmaliParse.parseMethods(text)
        val strings = SmaliParse.parseStrings(text)

        _state.value = _state.value.copy(
            filePath = filePath,
            fileName = file.name,
            workDir = workDirPath,
            rootDecompDir = root?.absolutePath,
            dexDirName = dexDir,
            dexVersion = version,
            loaded = true,
            content = text,
            originalContent = text,
            dirty = false,
            methods = methods,
            strings = strings
        )
        LogSink.i("Editor", "Opened ${file.absolutePath} (dex v$version)")
    }

    private fun resolveDexContext(workDir: File, file: File): Pair<File?, String?> {
        var cur: File? = file.parentFile
        while (cur != null) {
            val parent = cur.parentFile ?: break
            if (parent.absolutePath == workDir.absolutePath) break
            if (parent.name.endsWith("_dex_decomp")) {
                return parent to cur.name
            }
            cur = parent
        }
        return null to null
    }

    fun onContentChanged(newText: String) {
        val s = _state.value
        if (newText == s.content) return
        _state.value = s.copy(
            content = newText,
            dirty = newText != s.originalContent,
            methods = SmaliParse.parseMethods(newText),
            strings = SmaliParse.parseStrings(newText)
        )
    }

    fun save() = viewModelScope.launch {
        val s = _state.value
        if (!s.loaded) return@launch
        withContext(Dispatchers.IO) {
            runCatching { File(s.filePath).writeText(s.content) }
                .onFailure { LogSink.e("Editor", "Save failed for ${s.filePath}", it) }
        }
        s.rootDecompDir?.let { root ->
            s.dexDirName?.let { dir ->
                DexEditedStore.setEditedFlag(File(root), dir, 1)
                LogSink.i("Editor", "Marked ${File(root).name}/$dir as edited (value=1)")
            }
        }
        _state.value = s.copy(
            originalContent = s.content,
            dirty = false,
            info = getApplication<Application>().getString(R.string.editor_saved_info_fmt, s.fileName)
        )
    }

    fun autoSaveIfNeeded() {
        if (_state.value.prefs.autoSave && _state.value.dirty) save()
    }

    fun consumeInfo() {
        _state.value = _state.value.copy(info = null)
    }

    /**
     * Tìm file smali tương ứng type trong cùng workspace.
     * Ví dụ Lcom/foo/Bar; → tìm trong tất cả *_dex_decomp/<classesN>/com/foo/Bar.smali
     */
    fun resolveTypeToFile(type: String): File? {
        val workDir = File(_state.value.workDir)
        val rel = SmaliParse.typeToSmaliRelPath(type)
        val decompDirs = workDir.listFiles { f -> f.isDirectory && f.name.endsWith("_dex_decomp") }
            ?: return null
        for (dir in decompDirs) {
            val subDirs = dir.listFiles { f -> f.isDirectory } ?: continue
            for (sub in subDirs) {
                val candidate = File(sub, rel)
                if (candidate.isFile) return candidate
            }
        }
        return null
    }
}
