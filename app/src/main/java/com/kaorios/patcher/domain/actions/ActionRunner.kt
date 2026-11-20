package com.kaorios.patcher.domain.actions

import android.content.Context
import com.kaorios.patcher.data.log.LogSink
import com.kaorios.patcher.data.workspace.DexEditedStore
import com.kaorios.patcher.util.FileIo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files

/**
 * Chạy 1 ActionScript với cơ chế snapshot → patch → commit/rollback.
 *
 * 1. Tạo thư mục `<workDir>/<script.workingDirName>/`.
 * 2. Với mỗi op: locate file → copy vào snapshot dir giữ nguyên relative path → khởi tạo entry.json
 *    với key = 0.
 * 3. Chạy transform trên bản snapshot. Gặp lỗi → set entry[key] = 1 (không bỏ dở các op khác).
 * 4. Cuối script: nếu có entry nào = 1 → xoá snapshot dir (rollback toàn bộ, KHÔNG commit).
 *    Nếu tất cả = 0 → copy snapshot files đè file gốc → đánh dấu các dex chứa file đã sửa value=1
 *    trong dex_edited.json → xoá snapshot dir.
 */
class ActionRunner(
    private val ctx: Context,
    private val workDir: File,
    private val roots: List<File>,
    private val patchMode: PatchMode = PatchMode.ALL
) {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    data class RunResult(
        val scriptId: String,
        val ok: Boolean,
        val entries: Map<String, Int>,
        val error: String? = null
    )

    suspend fun run(script: ActionScript, progress: (String) -> Unit): RunResult =
        withContext(Dispatchers.IO) {
            val snapshotDir = File(workDir, script.workingDirName).apply {
                FileIo.deleteRecursively(this); mkdirs()
            }
            LogSink.section("Actions", script.id)
            LogSink.d("Actions", "snapshot dir: ${snapshotDir.absolutePath}")

            val entry = linkedMapOf<String, Int>()
            script.operations.forEach { entry[it.key] = 0 }
            writeEntry(snapshotDir, entry)

            // Script chỉ có hook chạy ở mode 2/3 sẽ lọc sạch operation → không được coi là
            // thành công rồi chạy afterCommit (import dex mà chẳng patch gì).
            val ops = script.operations.filter { patchMode.includes(it.patchMode) }
            if (script.operations.isNotEmpty() && ops.isEmpty()) {
                FileIo.deleteRecursively(snapshotDir)
                LogSink.w("Actions", "SKIP · ${script.id} — không có op nào thuộc $patchMode")
                return@withContext RunResult(
                    scriptId = script.id,
                    ok = false,
                    entries = entry,
                    error = "Script ${script.id} không có operation nào thuộc chế độ $patchMode"
                )
            }

            val snapshotMap = mutableMapOf<String, SnapshotFile>() // opKey → snapshot info

            // Pass 1: copy file + init entry
            for (op in ops) {
                progress("[${script.id}] chuẩn bị ${op.key}")
                try {
                    val matches = SmaliEngine.findAllForClass(roots, op.classDescriptor)
                    // Fail-closed: cùng một class ở nhiều classesN là layout mơ hồ, không đoán.
                    check(matches.size <= 1) {
                        "Unsupported layout: ambiguous duplicate target filenames (${op.classDescriptor})"
                    }
                    val srcFile = matches.firstOrNull()
                    if (srcFile == null) {
                        if (op.optional) {
                            LogSink.d("Actions", "prep SKIP · ${op.key} (không có ${op.classDescriptor})")
                            continue
                        }
                        throw IllegalStateException("Không tìm file cho ${op.classDescriptor}")
                    }
                    check(!Files.isSymbolicLink(srcFile.toPath())) {
                        "Unsupported layout: symbolic-link target (${srcFile.name})"
                    }
                    val rel = srcFile.relativeTo(workDir).path.replace('\\', '/')
                    val snapFile = File(snapshotDir, rel).apply { parentFile?.mkdirs() }
                    srcFile.copyTo(snapFile, overwrite = true)
                    snapshotMap[op.key] = SnapshotFile(srcFile, snapFile, rel, srcFile.readBytes())
                    LogSink.d("Actions", "prep copy: ${srcFile.name} ← $rel")
                } catch (t: Throwable) {
                    LogSink.e("Actions", "prep FAIL · ${op.key}", t)
                    entry[op.key] = 1
                }
            }
            writeEntry(snapshotDir, entry)
            val prepOk = snapshotMap.size
            val prepFail = entry.values.count { it == 1 }
            LogSink.detail("Actions", "prep · $prepOk file(s) ready" + if (prepFail > 0) ", $prepFail failed" else "")

            // Pass 2: patch từng op
            for (op in ops) {
                if (entry[op.key] == 1) {
                    LogSink.w("Actions", "patch SKIP · ${op.key} (prep failed)")
                    continue
                }
                val snap = snapshotMap[op.key] ?: run {
                    if (op.optional) {
                        LogSink.d("Actions", "patch SKIP · ${op.key} (không có file mục tiêu)")
                        entry[op.key] = 0
                    } else {
                        entry[op.key] = 1
                    }
                    continue
                }
                progress("[${script.id}] patch ${op.key}")
                try {
                    applyOp(op, snap.snapFile)
                    LogSink.detail("Actions", "patch OK · ${op.key}")
                } catch (t: Throwable) {
                    LogSink.e("Actions", "patch FAIL · ${op.key}: ${t.message}", t)
                    entry[op.key] = 1
                }
            }
            writeEntry(snapshotDir, entry)

            val hasError = entry.values.any { it == 1 }
            if (hasError) {
                val failed = entry.filterValues { it == 1 }.keys.joinToString(", ")
                LogSink.w("Actions", "ROLLBACK · ${script.id} — failed: $failed")
                FileIo.deleteRecursively(snapshotDir)
                return@withContext RunResult(
                    scriptId = script.id,
                    ok = false,
                    entries = entry,
                    error = "Rollback do có op lỗi: ${entry.filterValues { it == 1 }.keys.joinToString()}"
                )
            }

            // Commit: copy snapshot files đè lên gốc + đánh dấu dex dirty.
            // Fail-closed: file gốc đổi giữa lúc preflight và lúc commit → không ghi gì cả.
            val changedDuringPreflight = snapshotMap.values.filter {
                !it.originalFile.isFile || !it.originalFile.readBytes().contentEquals(it.originalBytes)
            }
            if (changedDuringPreflight.isNotEmpty()) {
                val names = changedDuringPreflight.joinToString { it.originalFile.name }
                LogSink.w("Actions", "ROLLBACK · ${script.id} — target changed during preflight: $names")
                FileIo.deleteRecursively(snapshotDir)
                return@withContext RunResult(
                    scriptId = script.id,
                    ok = false,
                    entries = entry,
                    error = "Target changed during preflight; no files written: $names"
                )
            }
            progress("[${script.id}] commit ${snapshotMap.size} file(s)")
            val touchedDex = mutableSetOf<Pair<File /*root*/, String /*dexName*/>>()
            for (snap in snapshotMap.values) {
                snap.snapFile.copyTo(snap.originalFile, overwrite = true)
                val (root, dexName) = resolveDexContext(snap.originalFile) ?: continue
                touchedDex.add(root to dexName)
            }
            touchedDex.forEach { (root, dexName) ->
                DexEditedStore.setEditedFlag(root, dexName, 1)
            }
            val dirtySummary = touchedDex.joinToString { "${it.first.name}/${it.second}" }
            LogSink.detail("Actions", "commit · ${snapshotMap.size} file(s) → mark dirty: $dirtySummary")
            FileIo.deleteRecursively(snapshotDir)

            // afterCommit: chạy các bước post-process (download, xóa folder theo tên, ...).
            // Lỗi trong đây không rollback (file gốc đã replace) — chỉ mark ok = false + ghi error.
            try {
                LogSink.section("Actions", "${script.id} · afterCommit")
                script.afterCommit(ctx, workDir, roots) { stage ->
                    LogSink.detail("Actions", stage)
                    progress("[${script.id}] $stage")
                }
                LogSink.section("Actions", "${script.id} · done")
                RunResult(script.id, ok = true, entries = entry)
            } catch (t: Throwable) {
                LogSink.e("Actions", "afterCommit FAILED · ${script.id}", t)
                RunResult(
                    scriptId = script.id,
                    ok = false,
                    entries = entry,
                    error = "Post-commit failed: ${t.message}"
                )
            }
        }

    private fun applyOp(op: ScriptOp, snapshotFile: File) {
        if (op.fileTransform != null) {
            val transformed = op.fileTransform.invoke(snapshotFile.readText())
            snapshotFile.writeText(transformed)
            return
        }
        val slice = when (val anchor = op.methodAnchorLineContains) {
            null -> SmaliEngine.extractMethod(snapshotFile, op.methodSignature)
                ?: error("Không tìm method `${op.methodSignature}` trong ${snapshotFile.name}")
            else -> SmaliEngine.extractMethodContainingLine(snapshotFile, anchor)
                ?: error(
                    "Không tìm method chứa dòng `$anchor` trong ${snapshotFile.name}"
                )
        }
        val mut = SmaliEngine.toMutable(slice)
        if (op.checkBannedWords) {
            val bad = SmaliEngine.findBannedKeyword(mut)
            if (bad != null) error("Gặp từ cấm `$bad` trong body method — bỏ qua.")
        }
        op.transform(mut)
        SmaliEngine.writeMethodBack(slice, mut)
    }

    /** Đi ngược từ file smali tới thư mục cha của classesN, trả (rootDecomp, dexName). */
    private fun resolveDexContext(f: File): Pair<File, String>? {
        var cur: File? = f.parentFile
        while (cur != null) {
            val parent = cur.parentFile ?: return null
            if (parent.name.endsWith("_dex_decomp")) return parent to cur.name
            cur = parent
        }
        return null
    }

    private fun writeEntry(dir: File, entries: Map<String, Int>) {
        File(dir, "entry.json").writeText(json.encodeToString(entries))
    }

    private data class SnapshotFile(
        val originalFile: File,
        val snapFile: File,
        val relPath: String,
        /** Nội dung gốc lúc preflight — dùng để phát hiện file bị sửa giữa chừng. */
        val originalBytes: ByteArray
    )
}
