package com.kaorios.patcher.ui.screen.browser

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kaorios.patcher.R
import com.kaorios.patcher.data.log.LogSink
import com.kaorios.patcher.data.workspace.DexEditedStore
import com.kaorios.patcher.domain.actions.ActionRunner
import com.kaorios.patcher.domain.actions.ActionState
import com.kaorios.patcher.domain.actions.ActionsRegistry
import com.kaorios.patcher.domain.actions.DexDownloader
import com.kaorios.patcher.domain.actions.PatchMode
import com.kaorios.patcher.domain.actions.Toolbox2036Config
import com.kaorios.patcher.domain.actions.UpdateKaoriosDexConfig
import com.kaorios.patcher.domain.actions.scripts.DexRedivisionScript
import com.kaorios.patcher.domain.actions.scripts.DisableSecureFlagScript
import com.kaorios.patcher.domain.actions.scripts.KaoriosToolbox2036Script
import com.kaorios.patcher.domain.actions.scripts.UpdateKaoriosDexScript
import com.kaorios.patcher.domain.model.DexEditedEntry
import com.kaorios.patcher.domain.model.WorkMode
import com.kaorios.patcher.domain.usecase.BuildUseCase
import com.kaorios.patcher.domain.usecase.ExportMergedDexUseCase
import com.kaorios.patcher.domain.usecase.ImportDexUseCase
import com.kaorios.patcher.util.FileIo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * File browser ViewModel:
 * - **Virtual merge** (mặc định bật): gộp các `classesN/` ngay dưới root thành 1 level ảo.
 *   User có thể tắt qua toggle trong menu để xem cấu trúc thật.
 * - **Selection mode**: long-press node để vào chế độ chọn nhiều, click toggles. Khi có selection,
 *   bật các action Xóa / Export merged dex.
 * - **Lazy loading** + **2 panel song song** cho KaoriosPatcher.
 */
class FileBrowserViewModel(app: Application) : AndroidViewModel(app) {

    data class Node(
        val isDir: Boolean,
        val depth: Int,
        val displayName: String,
        val virtualKey: String,
        val rootDecompDir: File,
        val realFiles: List<File>
    ) {
        val primaryFile: File get() = realFiles.first()
        fun expandKey(): String = "${rootDecompDir.absolutePath}|$virtualKey"
    }

    data class DexInfo(val name: String, val entry: DexEditedEntry)

    data class UiState(
        val workDir: File? = null,
        val mode: WorkMode = WorkMode.KAORIOS_PATCHER,
        val loading: Boolean = false,
        val virtualMerge: Boolean = true,
        val expanded: Set<String> = emptySet(),
        val childrenCache: Map<String, List<Node>> = emptyMap(),
        val roots: List<Node> = emptyList(),
        val editedValues: Map<String, Map<String, DexEditedEntry>> = emptyMap(),
        val visibleNodes: List<Node> = emptyList(),
        val query: String = "",
        val searchResults: List<Node> = emptyList(),
        val searching: Boolean = false,
        val selectedKeys: Set<String> = emptySet(),
        val building: Boolean = false,
        val buildStage: String = "",
        val buildError: String? = null,
        val buildDone: String? = null,
        val toast: String? = null
    ) {
        val selectionMode: Boolean get() = selectedKeys.isNotEmpty()
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    fun load(workDirPath: String) = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true)
        val workDir = File(workDirPath)
        val decompDirs = withContext(Dispatchers.IO) {
            workDir.listFiles()
                ?.filter { it.isDirectory && it.name.endsWith("_dex_decomp") }
                ?.sortedBy { it.name }
                ?: emptyList()
        }
        val mode = detectMode(decompDirs)
        val edited = withContext(Dispatchers.IO) {
            decompDirs.associate { dir -> dir.absolutePath to DexEditedStore.read(dir) }
        }
        val roots = decompDirs.map { dir ->
            Node(
                isDir = true, depth = 0, displayName = dir.name,
                virtualKey = "", rootDecompDir = dir, realFiles = listOf(dir)
            )
        }
        val expanded = roots.map { it.expandKey() }.toSet()

        val virtual = _state.value.virtualMerge
        val cache = mutableMapOf<String, List<Node>>()
        withContext(Dispatchers.IO) {
            for (root in roots) {
                cache[root.expandKey()] = listChildren(root, virtual)
            }
        }

        val newState = _state.value.copy(
            workDir = workDir, mode = mode, loading = false,
            expanded = expanded, childrenCache = cache, roots = roots,
            editedValues = edited, selectedKeys = emptySet()
        )
        _state.value = buildVisible(newState)

        LogSink.section("Browser", "đã load ${roots.size} root · $mode · merge=$virtual")
    }

    private fun detectMode(decompDirs: List<File>): WorkMode {
        val names = decompDirs.map { it.name.removeSuffix("_dex_decomp") }.toSet()
        return when {
            names.containsAll(setOf("framework", "services")) -> WorkMode.KAORIOS_PATCHER
            else -> WorkMode.OTHER_JAR
        }
    }

    private fun classesDirs(root: File): List<File> =
        root.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("classes", ignoreCase = true) }
            ?.sortedBy { naturalKey(it.name) }
            ?: emptyList()

    private fun naturalKey(name: String): String {
        val num = name.removePrefix("classes").ifEmpty { "1" }
        return num.padStart(6, '0')
    }

    // ----------------------- Tree building -----------------------

    private fun listChildren(parent: Node, virtual: Boolean): List<Node> =
        if (virtual) listChildrenMerged(parent) else listChildrenRaw(parent)

    private fun listChildrenMerged(parent: Node): List<Node> {
        val root = parent.rootDecompDir
        val sourceDirs: List<File> = if (parent.depth == 0) {
            classesDirs(root)
        } else {
            parent.realFiles.filter { it.isDirectory }
        }
        if (sourceDirs.isEmpty()) return emptyList()

        data class Bucket(var isDir: Boolean, val files: MutableList<File> = mutableListOf())
        val bucket = linkedMapOf<String, Bucket>()
        for (src in sourceDirs) {
            val children = src.listFiles() ?: continue
            for (c in children) {
                if (c.isFile && c.name == DexEditedStore.FILE_NAME) continue
                val existing = bucket[c.name]
                if (existing == null) {
                    bucket[c.name] = Bucket(c.isDirectory).apply { files.add(c) }
                } else {
                    if (existing.isDir != c.isDirectory) {
                        if (c.isDirectory && !existing.isDir) {
                            existing.isDir = true; existing.files.clear(); existing.files.add(c)
                        }
                        continue
                    }
                    existing.files.add(c)
                }
            }
        }
        val depth = parent.depth + 1
        val parentPath = parent.virtualKey
        return bucket.entries.asSequence()
            .map { (name, buck) ->
                val vkey = if (parentPath.isEmpty()) name else "$parentPath/$name"
                Node(
                    isDir = buck.isDir, depth = depth, displayName = name,
                    virtualKey = vkey, rootDecompDir = root, realFiles = buck.files
                )
            }
            .sortedWith(compareBy<Node> { !it.isDir }.thenBy { it.displayName.lowercase() })
            .toList()
    }

    /** Duyệt cây như bình thường, không gộp. */
    private fun listChildrenRaw(parent: Node): List<Node> {
        val srcDir = parent.primaryFile
        val children = srcDir.listFiles()
            ?.filter { !(it.isFile && it.name == DexEditedStore.FILE_NAME) }
            ?: return emptyList()
        val depth = parent.depth + 1
        return children.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() })
            .map { f ->
                val vkey = if (parent.virtualKey.isEmpty()) f.name else "${parent.virtualKey}/${f.name}"
                Node(
                    isDir = f.isDirectory, depth = depth, displayName = f.name,
                    virtualKey = vkey, rootDecompDir = parent.rootDecompDir,
                    realFiles = listOf(f)
                )
            }
    }

    fun toggleVirtualMerge() = viewModelScope.launch {
        val s = _state.value
        val newVirtual = !s.virtualMerge
        // Cache không còn đúng — wipe và build lại cho roots. Expand state giữ lại.
        val freshCache = mutableMapOf<String, List<Node>>()
        withContext(Dispatchers.IO) {
            for (root in s.roots) {
                freshCache[root.expandKey()] = listChildren(root, newVirtual)
            }
        }
        _state.value = buildVisible(
            s.copy(
                virtualMerge = newVirtual,
                childrenCache = freshCache,
                expanded = s.roots.map { it.expandKey() }.toSet(),
                selectedKeys = emptySet()
            )
        )
        LogSink.i("Browser", "virtualMerge toggled to $newVirtual")
    }

    fun toggleExpand(node: Node) = viewModelScope.launch {
        val s = _state.value
        val key = node.expandKey()
        val nowExpanded = key !in s.expanded
        val newExpanded = if (nowExpanded) s.expanded + key else s.expanded - key
        val newCache = s.childrenCache.toMutableMap()
        if (nowExpanded && key !in newCache) {
            val loaded = withContext(Dispatchers.IO) { listChildren(node, s.virtualMerge) }
            newCache[key] = loaded
        }
        _state.value = buildVisible(s.copy(expanded = newExpanded, childrenCache = newCache))
    }

    private fun buildVisible(s: UiState): UiState {
        if (s.query.isNotBlank()) return s.copy(visibleNodes = s.searchResults)
        val out = mutableListOf<Node>()
        fun visit(node: Node) {
            out.add(node)
            if (node.isDir && node.expandKey() in s.expanded) {
                val children = s.childrenCache[node.expandKey()] ?: return
                children.forEach(::visit)
            }
        }
        s.roots.forEach(::visit)
        return s.copy(visibleNodes = out)
    }

    // ----------------------- Search -----------------------

    fun submitQuery(q: String) {
        searchJob?.cancel()
        if (q.isBlank()) { clearQuery(); return }
        val s = _state.value
        _state.value = s.copy(query = q, searching = true)
        searchJob = viewModelScope.launch {
            val results = withContext(Dispatchers.IO) {
                val out = mutableListOf<Node>()
                for (root in s.roots) {
                    searchMerged(root, q, out, limit = 500)
                    if (out.size >= 500) break
                }
                out
            }
            val cur = _state.value
            if (cur.query == q) {
                _state.value = cur.copy(
                    searchResults = results, searching = false, visibleNodes = results
                )
            }
        }
    }

    fun clearQuery() {
        searchJob?.cancel()
        val s = _state.value
        _state.value = buildVisible(s.copy(query = "", searchResults = emptyList(), searching = false))
    }

    /**
     * Các chế độ match query, quyết định khi submit:
     *
     * 1. `Lcom/foo/Bar;` (bắt đầu `L`, kết thúc `;`)    → [ExactPath] strip `L..;` → match đúng rel path `com/foo/Bar.smali`.
     * 2. `Bar;` (chỉ kết thúc `;`, không có `/`)         → [ExactName] strict filename `Bar.smali` (KHÔNG match `Bar$1.smali`).
     * 3. `com/foo/Bar;` (có `/` và kết thúc `;`)         → [ExactPath] → match rel `com/foo/Bar.smali`.
     * 4. `com/foo/` hoặc query chứa `/`                  → [PathContains] contains trên rel path.
     * 5. Còn lại                                         → [NameContains] contains trên filename (hiện tại).
     */
    private sealed class QueryMode {
        data class ExactPath(val rel: String) : QueryMode()
        data class ExactName(val baseName: String) : QueryMode()
        data class PathContains(val sub: String) : QueryMode()
        data class NameContains(val sub: String) : QueryMode()
    }

    private fun parseQueryMode(raw: String): QueryMode {
        val q = raw.trim()
        val withSemi = q.endsWith(';')
        val body = if (withSemi) q.substring(0, q.length - 1) else q
        val stripped = body.removePrefix("L")
        val hasSlash = stripped.contains('/')

        return when {
            withSemi && hasSlash -> QueryMode.ExactPath("$stripped.smali")
            withSemi && !hasSlash -> QueryMode.ExactName("$stripped.smali")
            !withSemi && hasSlash -> QueryMode.PathContains(stripped)
            else -> QueryMode.NameContains(stripped)
        }
    }

    private fun searchMerged(root: Node, needle: String, out: MutableList<Node>, limit: Int) {
        val mode = parseQueryMode(needle)

        // Fast path: ExactPath → compute direct file path trong từng classesN, không cần walk.
        if (mode is QueryMode.ExactPath) {
            for (cd in classesDirs(root.rootDecompDir)) {
                if (out.size >= limit) return
                val candidate = File(cd, mode.rel)
                if (candidate.isFile && candidate.name != DexEditedStore.FILE_NAME) {
                    out.add(
                        Node(
                            isDir = false, depth = 1, displayName = candidate.name,
                            virtualKey = mode.rel, rootDecompDir = root.rootDecompDir,
                            realFiles = listOf(candidate)
                        )
                    )
                }
            }
            return
        }

        for (cd in classesDirs(root.rootDecompDir)) {
            if (out.size >= limit) return
            searchRecursive(cd, "", root.rootDecompDir, mode, out, limit)
        }
    }

    private fun searchRecursive(
        dir: File, virtualPrefix: String, root: File, mode: QueryMode,
        out: MutableList<Node>, limit: Int
    ) {
        if (out.size >= limit) return
        val list = dir.listFiles() ?: return
        for (f in list) {
            if (out.size >= limit) return
            if (f.isFile) {
                if (f.name == DexEditedStore.FILE_NAME) continue
                val rel = if (virtualPrefix.isEmpty()) f.name else "$virtualPrefix/${f.name}"
                val matched = when (mode) {
                    is QueryMode.ExactName -> f.name.equals(mode.baseName, ignoreCase = true)
                    is QueryMode.PathContains -> rel.contains(mode.sub, ignoreCase = true)
                    is QueryMode.NameContains -> f.name.contains(mode.sub, ignoreCase = true)
                    is QueryMode.ExactPath -> false // handled in fast path
                }
                if (matched) {
                    out.add(
                        Node(
                            isDir = false, depth = 1, displayName = f.name,
                            virtualKey = rel, rootDecompDir = root, realFiles = listOf(f)
                        )
                    )
                }
            } else if (f.isDirectory) {
                val newPrefix = if (virtualPrefix.isEmpty()) f.name else "$virtualPrefix/${f.name}"
                searchRecursive(f, newPrefix, root, mode, out, limit)
            }
        }
    }

    // ----------------------- Selection -----------------------

    fun beginSelection(node: Node) {
        val key = node.expandKey()
        _state.value = _state.value.copy(selectedKeys = setOf(key))
    }

    fun toggleSelect(node: Node) {
        val s = _state.value
        val key = node.expandKey()
        val next = if (key in s.selectedKeys) s.selectedKeys - key else s.selectedKeys + key
        _state.value = s.copy(selectedKeys = next)
    }

    fun clearSelection() {
        _state.value = _state.value.copy(selectedKeys = emptySet())
    }

    private fun selectedNodes(): List<Node> {
        val s = _state.value
        val selected = s.selectedKeys
        // Tìm node từ visibleNodes. Fallback: đi qua roots + childrenCache.
        val byKey = mutableMapOf<String, Node>()
        fun collect(n: Node) {
            byKey[n.expandKey()] = n
            if (n.isDir) s.childrenCache[n.expandKey()]?.forEach(::collect)
        }
        s.roots.forEach(::collect)
        return selected.mapNotNull { byKey[it] }
    }

    // ----------------------- Dex marking -----------------------

    fun dexInfoFor(root: File): List<DexInfo> {
        val map = _state.value.editedValues[root.absolutePath] ?: emptyMap()
        val dirs = classesDirs(root).map { it.name }
        val keys = (dirs + map.keys).distinct().sortedBy { naturalKey(it) }
        return keys.map { name -> DexInfo(name, map[name] ?: DexEditedEntry(value = 0, version = 39)) }
    }

    fun setEditedValue(rootDir: File, dexName: String, value: Int) {
        DexEditedStore.setEditedFlag(rootDir, dexName, value)
        val map = DexEditedStore.read(rootDir)
        val s = _state.value
        _state.value = s.copy(
            editedValues = s.editedValues.toMutableMap().apply { this[rootDir.absolutePath] = map }
        )
        LogSink.i("Browser", "${rootDir.name}/$dexName marked value=$value")
    }

    // ----------------------- Delete selected -----------------------

    fun deleteSelected(onDone: (count: Int) -> Unit = {}) = viewModelScope.launch {
        val nodes = selectedNodes()
        if (nodes.isEmpty()) return@launch
        var totalDeleted = 0
        val affectedRoots = linkedSetOf<File>()
        withContext(Dispatchers.IO) {
            for (node in nodes) {
                node.realFiles.forEach { f ->
                    // Tránh xóa root decomp.
                    if (f.absolutePath == node.rootDecompDir.absolutePath) return@forEach
                    if (f.exists()) {
                        FileIo.deleteRecursively(f)
                        totalDeleted++
                    }
                }
                affectedRoots.add(node.rootDecompDir)
            }
            // Đánh dấu các dex liên quan = 1 (vì nội dung smali đã thay đổi).
            for (node in nodes) {
                val dexNames = node.realFiles.mapNotNull { dexNameOf(node.rootDecompDir, it) }.distinct()
                for (d in dexNames) DexEditedStore.setEditedFlag(node.rootDecompDir, d, 1)
            }
        }
        LogSink.i("Browser", "Deleted $totalDeleted real entries, marked ${affectedRoots.size} root(s) dirty")
        clearSelection()
        _state.value = _state.value.copy(
            editedValues = _state.value.editedValues.toMutableMap().apply {
                affectedRoots.forEach { this[it.absolutePath] = DexEditedStore.read(it) }
            },
            toast = getApplication<Application>().getString(R.string.browser_delete_toast_fmt, totalDeleted)
        )
        reloadCache()
        onDone(totalDeleted)
    }

    /** Lấy tên classesN chứa file (đi ngược parent đến khi gặp parent có tên bắt đầu "classes" nằm ngay dưới root). */
    private fun dexNameOf(root: File, f: File): String? {
        var cur: File? = f
        while (cur != null && cur.parentFile?.absolutePath != root.absolutePath) {
            cur = cur.parentFile
        }
        return cur?.name
    }

    private suspend fun reloadCache() {
        val s = _state.value
        val virtual = s.virtualMerge
        val fresh = mutableMapOf<String, List<Node>>()
        withContext(Dispatchers.IO) {
            // Reload các key đã expand để UI refresh. Root luôn giữ.
            val keysToReload = s.expanded + s.roots.map { it.expandKey() }
            // Để re-list được 1 key cụ thể ta cần Node → lấy từ childrenCache hoặc roots.
            val allKnown = mutableMapOf<String, Node>()
            s.roots.forEach { r -> allKnown[r.expandKey()] = r }
            s.childrenCache.values.flatten().forEach { n -> allKnown[n.expandKey()] = n }
            for (k in keysToReload) {
                val node = allKnown[k] ?: continue
                if (!node.isDir) continue
                // Cập nhật realFiles: lọc file còn tồn tại
                val stillAlive = node.realFiles.filter { it.exists() }
                if (stillAlive.isEmpty()) continue
                val updatedNode = node.copy(realFiles = stillAlive)
                fresh[k] = listChildren(updatedNode, virtual)
            }
        }
        _state.value = buildVisible(_state.value.copy(childrenCache = fresh))
    }

    // ----------------------- Import / Export -----------------------

    fun importDex(targetRoot: File, uri: Uri, onDone: (ok: Boolean, msg: String) -> Unit) =
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true)
            val result = runCatching {
                ImportDexUseCase(getApplication()).invoke(targetRoot, uri)
            }
            _state.value = _state.value.copy(loading = false)
            result.onSuccess { dexName ->
                LogSink.i("Browser", "Imported dex as $dexName into ${targetRoot.name}")
                onDone(
                    true,
                    getApplication<Application>().getString(
                        R.string.browser_import_ok_fmt,
                        targetRoot.name,
                        dexName
                    )
                )
                // refresh editedValues + cache
                val map = DexEditedStore.read(targetRoot)
                _state.value = _state.value.copy(
                    editedValues = _state.value.editedValues.toMutableMap()
                        .apply { this[targetRoot.absolutePath] = map }
                )
                reloadCache()
            }.onFailure {
                LogSink.e("Browser", "Import dex failed", it)
                onDone(false, it.message ?: it.toString())
            }
        }

    fun exportSelectedAsDex(outputUri: Uri, onDone: (ok: Boolean, msg: String) -> Unit) =
        viewModelScope.launch {
            val nodes = selectedNodes()
            if (nodes.isEmpty()) {
                onDone(false, getApplication<Application>().getString(R.string.browser_export_none_selected))
                return@launch
            }
            _state.value = _state.value.copy(loading = true)
            val result = runCatching {
                ExportMergedDexUseCase(getApplication()).invoke(nodes.flatMap { it.realFiles }, outputUri)
            }
            _state.value = _state.value.copy(loading = false)
            result.onSuccess { count ->
                onDone(true, getApplication<Application>().getString(R.string.browser_export_ok_fmt, count))
            }.onFailure {
                LogSink.e("Browser", "Export merged dex failed", it)
                onDone(false, it.message ?: it.toString())
            }
        }

    fun consumeToast() { _state.value = _state.value.copy(toast = null) }

    // ----------------------- Build -----------------------

    fun build(treeUri: Uri?, kind: BuildUseCase.BuildKind = BuildUseCase.BuildKind.JAR) =
        viewModelScope.launch {
            val s = _state.value
            val workDir = s.workDir ?: return@launch
            _state.value = s.copy(
                building = true,
                buildStage = getApplication<Application>().getString(R.string.progress_starting),
                buildError = null,
                buildDone = null
            )
            runCatching {
                BuildUseCase(getApplication()).invoke(workDir, s.mode, kind, treeUri) { stage ->
                    _state.value = _state.value.copy(buildStage = stage)
                }
            }.onSuccess { r ->
                val app = getApplication<Application>()
                val msg = when (r.kind) {
                    BuildUseCase.BuildKind.JAR -> app.getString(
                        R.string.build_done_jar_fmt,
                        r.compiledDex,
                        r.outputs.size
                    )
                    BuildUseCase.BuildKind.MODULE -> app.getString(
                        R.string.build_done_module_fmt,
                        r.compiledDex,
                        r.moduleTag ?: app.getString(R.string.label_latest)
                    )
                }
                LogSink.i("Browser", msg)
                _state.value = _state.value.copy(building = false, buildDone = msg, buildError = null)
            }.onFailure { t ->
                LogSink.e("Browser", "Build failed", t)
                _state.value = _state.value.copy(building = false, buildError = t.message ?: t.toString())
            }
        }

    fun consumeBuildNotice() {
        _state.value = _state.value.copy(buildDone = null, buildError = null)
    }

    // ----------------------- Actions -----------------------

    data class ActionRunUi(
        val running: Boolean = false,
        val stage: String = "",
        val results: List<ActionRunner.RunResult> = emptyList()
    )

    /**
     * Trạng thái chờ nhập tag cho Toolbox hoặc UpdateKaoriosDex trước khi thực sự chạy actions.
     * [scriptId] xác định script nào đang hỏi tag — dùng để pick đúng Config để update.
     * [scriptName] hiển thị trên tiêu đề dialog.
     */
    data class TagInputUi(
        val selected: List<ActionsRegistry.Entry> = emptyList(),
        val patchMode: PatchMode = PatchMode.ORIGINAL,
        val scriptId: String = ActionState.SCRIPT_ID_TOOLBOX_2036,
        val scriptName: String = "",
        val tag: String = Toolbox2036Config.DEFAULT_TAG,
        val validating: Boolean = false,
        val error: String? = null,
        val showing: Boolean = false
    )

    /**
     * Trạng thái chờ nhập limit cho DexRedivisionScript.
     */
    data class LimitInputUi(
        val selected: List<ActionsRegistry.Entry> = emptyList(),
        val patchMode: PatchMode = PatchMode.ORIGINAL,
        val limit: String = "64000",
        val error: String? = null,
        val showing: Boolean = false
    )

    /**
     * Cảnh báo đỏ với countdown 5s trước khi cho phép tiếp tục.
     * Dùng cho UpdateKaoriosDex / DisableSecureFlag — các script có ràng buộc đặc thù.
     */
    data class WarnUi(
        val selected: List<ActionsRegistry.Entry> = emptyList(),
        val patchMode: PatchMode = PatchMode.ORIGINAL,
        val scriptIds: List<String> = emptyList(),
        val remainingSec: Int = 5,
        val showing: Boolean = false
    )

    private val _actionState = MutableStateFlow(ActionRunUi())
    val actionState: StateFlow<ActionRunUi> = _actionState.asStateFlow()

    private val _tagInput = MutableStateFlow(TagInputUi())
    val tagInput: StateFlow<TagInputUi> = _tagInput.asStateFlow()

    private val _limitInput = MutableStateFlow(LimitInputUi())
    val limitInput: StateFlow<LimitInputUi> = _limitInput.asStateFlow()

    private val _warn = MutableStateFlow(WarnUi())
    val warn: StateFlow<WarnUi> = _warn.asStateFlow()

    private var warnJob: Job? = null

    /**
     * Preflight trước khi chạy:
     * 1. Nếu có UpdateKaoriosDex / DisableSecureFlag → show cảnh báo đỏ 5s trước.
     * 2. Nếu có DexRedivisionScript → show limit dialog.
     * 3. Nếu có Toolbox hoặc UpdateKaoriosDex → show tag dialog.
     * 4. Còn lại → chạy thẳng.
     *
     * Các bước tách rời để có thể chaining: warning → limit/tag → execute.
     */
    fun requestRunActions(selected: List<ActionsRegistry.Entry>, patchMode: PatchMode) {
        val warnIds = selected.map { it.script.id }.filter {
            it == ActionState.SCRIPT_ID_UPDATE_KAORIOS_DEX ||
                it == DisableSecureFlagScript.id
        }
        if (warnIds.isNotEmpty()) {
            startWarnCountdown(selected, patchMode, warnIds)
            return
        }
        proceedAfterWarn(selected, patchMode)
    }

    private fun proceedAfterWarn(selected: List<ActionsRegistry.Entry>, patchMode: PatchMode) {
        val hasRedivision = selected.any { it.script.id == DexRedivisionScript.id }
        if (hasRedivision) {
            _limitInput.value = LimitInputUi(selected = selected, patchMode = patchMode, showing = true)
            return
        }

        val tagOwner = selected.firstOrNull {
            it.script.id == ActionState.SCRIPT_ID_TOOLBOX_2036 ||
                it.script.id == ActionState.SCRIPT_ID_UPDATE_KAORIOS_DEX
        }
        if (tagOwner != null) {
            val (currentTag, defaultTag) = when (tagOwner.script.id) {
                ActionState.SCRIPT_ID_UPDATE_KAORIOS_DEX -> UpdateKaoriosDexConfig.currentTag to UpdateKaoriosDexConfig.DEFAULT_TAG
                else -> Toolbox2036Config.currentTag to Toolbox2036Config.DEFAULT_TAG
            }
            _tagInput.value = TagInputUi(
                selected = selected,
                patchMode = patchMode,
                scriptId = tagOwner.script.id,
                scriptName = tagOwner.script.displayName,
                tag = currentTag.ifBlank { defaultTag },
                showing = true
            )
        } else {
            executeActions(selected, patchMode)
        }
    }

    private fun startWarnCountdown(
        selected: List<ActionsRegistry.Entry>,
        patchMode: PatchMode,
        ids: List<String>
    ) {
        warnJob?.cancel()
        _warn.value = WarnUi(
            selected = selected,
            patchMode = patchMode,
            scriptIds = ids,
            remainingSec = 5,
            showing = true
        )
        warnJob = viewModelScope.launch {
            while (_warn.value.showing && _warn.value.remainingSec > 0) {
                kotlinx.coroutines.delay(1000)
                val cur = _warn.value
                if (!cur.showing) return@launch
                _warn.value = cur.copy(remainingSec = (cur.remainingSec - 1).coerceAtLeast(0))
            }
        }
    }

    fun cancelWarn() {
        warnJob?.cancel()
        _warn.value = WarnUi()
    }

    fun confirmWarnAndProceed() {
        val snap = _warn.value
        if (!snap.showing || snap.remainingSec > 0) return
        warnJob?.cancel()
        val selected = snap.selected
        val patchMode = snap.patchMode
        _warn.value = WarnUi()
        proceedAfterWarn(selected, patchMode)
    }

    fun updatePendingLimit(limit: String) {
        val filtered = limit.filter { it.isDigit() }
        val limitInt = filtered.toIntOrNull()
        val error = when {
            limitInt == null && filtered.isNotEmpty() -> getApplication<Application>().getString(R.string.picker_error_limit_range)
            limitInt != null && (limitInt < 20000 || limitInt > 64000) -> getApplication<Application>().getString(R.string.picker_error_limit_range)
            else -> null
        }
        _limitInput.value = _limitInput.value.copy(limit = filtered, error = error)
    }

    fun cancelLimitInput() {
        _limitInput.value = LimitInputUi()
    }

    fun confirmLimitAndRun() {
        val snap = _limitInput.value
        if (!snap.showing) return
        val limitInt = snap.limit.toIntOrNull()
        if (limitInt == null || limitInt < 20000 || limitInt > 64000) {
            _limitInput.value = snap.copy(error = getApplication<Application>().getString(R.string.picker_error_limit_range))
            return
        }
        val workDir = _state.value.workDir ?: return
        DexRedivisionScript.saveRedivisionLimit(workDir, limitInt)
        _limitInput.value = LimitInputUi()
        executeActions(snap.selected, snap.patchMode)
    }

    fun updatePendingTag(tag: String) {
        _tagInput.value = _tagInput.value.copy(tag = tag, error = null)
    }

    fun cancelTagInput() {
        _tagInput.value = TagInputUi()
    }

    /** Validate tag qua GitHub API. Nếu OK → close dialog + run. Else → show error trong dialog. */
    fun confirmTagAndRun() = viewModelScope.launch {
        val snap = _tagInput.value
        if (!snap.showing || snap.validating) return@launch
        val tag = snap.tag.trim()
        if (tag.isEmpty()) {
            _tagInput.value = snap.copy(
                error = getApplication<Application>().getString(R.string.browser_tag_dialog_empty)
            )
            return@launch
        }
        _tagInput.value = snap.copy(validating = true, error = null)
        // Toolbox và UpdateKaoriosDex cùng repo — dùng REPO_* của 2036 làm chung.
        val res = runCatching {
            withContext(Dispatchers.IO) {
                DexDownloader.resolveReleaseAsset(
                    KaoriosToolbox2036Script.REPO_OWNER,
                    KaoriosToolbox2036Script.REPO_NAME,
                    tag,
                    "dex"
                )
            }
        }
        res.onSuccess { asset ->
            LogSink.detail("Actions", "release tag OK · $tag → ${asset.name} (${asset.size}B) for ${snap.scriptId}")
            when (snap.scriptId) {
                ActionState.SCRIPT_ID_UPDATE_KAORIOS_DEX -> UpdateKaoriosDexConfig.update(tag)
                else -> Toolbox2036Config.update(tag)
            }
            val selected = snap.selected
            val patchMode = snap.patchMode
            _tagInput.value = TagInputUi()
            executeActions(selected, patchMode)
        }.onFailure { t ->
            LogSink.w("Actions", "release tag FAIL · $tag (${snap.scriptId}): ${t.message}", t)
            _tagInput.value = snap.copy(
                validating = false,
                error = t.message ?: getApplication<Application>().getString(R.string.browser_tag_validate_failed)
            )
        }
    }

    private fun executeActions(selected: List<ActionsRegistry.Entry>, patchMode: PatchMode) = viewModelScope.launch {
        val workDir = _state.value.workDir ?: return@launch
        val roots = _state.value.roots.map { it.rootDecompDir }
        if (roots.isEmpty()) {
            LogSink.w("Actions", "run scripts: no roots (workspace not ready)")
            return@launch
        }
        val ordered = ActionsRegistry.sortForExecution(selected)
        LogSink.section("Actions", "run ${ordered.size} script(s): ${ordered.joinToString { it.script.id }}")
        _actionState.value = ActionRunUi(
            running = true,
            stage = getApplication<Application>().getString(R.string.progress_starting)
        )
        val runner = ActionRunner(getApplication(), workDir, roots, patchMode = patchMode)
        val results = mutableListOf<ActionRunner.RunResult>()
        for (entry in ordered) {
            _actionState.value = _actionState.value.copy(
                stage = getApplication<Application>().getString(
                    R.string.progress_action_running_fmt,
                    entry.script.id
                )
            )
            val r = runner.run(entry.script) { stage ->
                _actionState.value = _actionState.value.copy(stage = stage)
            }
            results.add(r)
            if (!r.ok) {
                LogSink.w("Actions", "stopped after FAIL · ${entry.script.id}")
                break
            }
        }
        val edited = withContext(Dispatchers.IO) {
            roots.associate { it.absolutePath to DexEditedStore.read(it) }
        }
        _state.value = _state.value.copy(editedValues = edited)
        _actionState.value = ActionRunUi(
            running = false,
            stage = getApplication<Application>().getString(R.string.progress_actions_done),
            results = results
        )
    }

    fun consumeActionResults() { _actionState.value = ActionRunUi() }
}
