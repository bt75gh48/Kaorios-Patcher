package com.kaorios.patcher.domain.usecase

import android.content.Context
import android.net.Uri
import com.kaorios.patcher.data.dex.Baksmali
import com.kaorios.patcher.data.dex.DexIo
import com.kaorios.patcher.data.dex.DexRedivision
import com.kaorios.patcher.data.jar.JarArchiver
import com.kaorios.patcher.data.log.LogSink
import com.kaorios.patcher.data.workspace.DexEditedStore
import com.kaorios.patcher.R
import com.kaorios.patcher.data.workspace.Workspace
import com.kaorios.patcher.domain.model.DexEditedEntry
import com.kaorios.patcher.domain.model.JarSlot
import com.kaorios.patcher.domain.model.WorkMode
import com.kaorios.patcher.domain.model.WorkSession
import com.kaorios.patcher.util.FileIo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Chuẩn bị workspace theo đúng mô tả của user:
 *
 * - Tạo `workDir = .../work/<ts>_<mode>/`.
 * - Copy jar vào `<workDir>/<logicalName>.jar`.
 * - Unzip vào `<logicalName>_jar_decomp/`.
 * - Tất cả `classes*.dex` trong jar được decompile vào `<logicalName>_dex_decomp/<dexName>/`.
 *   - Mode KAORIOS/OTHER_JAR: mỗi dex → 1 subfolder riêng.
 * - Ghi `dex_edited.json`.
 */
class PrepareWorkspaceUseCase(private val ctx: Context) {

    data class Input(
        val mode: WorkMode,
        val inputs: List<JarInput>
    ) {
        /**
         * @param logicalName tên luận lý: `framework`/`services` với mode KaoriosPatcher,
         *                    lấy từ filename (không đuôi) cho các mode còn lại.
         * @param source nguồn jar: có thể từ SAF ([Source.Saf]) hoặc trực tiếp từ filesystem
         *               ([Source.FilePath] — dùng cho `/system/framework`).
         */
        data class JarInput(
            val logicalName: String,
            val displayName: String,
            val source: Source
        )

        sealed interface Source {
            data class Saf(val uri: Uri) : Source
            data class FilePath(val path: String) : Source
        }
    }

    suspend fun invoke(
        input: Input,
        progress: (stage: String, current: Int, total: Int) -> Unit = { _, _, _ -> }
    ): WorkSession = withContext(Dispatchers.IO) {
        progress(ctx.getString(R.string.build_progress_wiping_old), 0, input.inputs.size)
        val workDir = Workspace.newSessionDir(ctx, input.mode)
        LogSink.section("Prepare", "workspace mới")
        LogSink.detail("Prepare", workDir.absolutePath)

        val slots = mutableListOf<JarSlot>()
        val totalJars = input.inputs.size
        input.inputs.forEachIndexed { index, jin ->
            progress(ctx.getString(R.string.build_copy_jar_fmt, jin.displayName), index, totalJars)
            val jarCopy = Workspace.workingJarCopy(workDir, jin.logicalName)
            when (val src = jin.source) {
                is Input.Source.Saf -> {
                    FileIo.copyUriToFile(ctx.contentResolver, src.uri, jarCopy)
                    LogSink.detail("Prepare", "${jin.logicalName}.jar ← SAF")
                    LogSink.d("Prepare", "  ${src.uri}")
                }
                is Input.Source.FilePath -> {
                    val srcFile = File(src.path)
                    if (!srcFile.canRead()) {
                        error(ctx.getString(R.string.prepare_error_unreadable_fmt, srcFile.absolutePath))
                    }
                    srcFile.inputStream().use { ins ->
                        jarCopy.outputStream().use { out -> FileIo.copy(ins, out) }
                    }
                    LogSink.detail("Prepare", "${jin.logicalName}.jar ← ${srcFile.absolutePath}")
                }
            }

            val jarDecompDir = Workspace.jarDecompDir(workDir, jin.logicalName)
            val dexDecompDir = Workspace.dexDecompDir(workDir, jin.logicalName)

            progress(ctx.getString(R.string.build_extract_fmt, jarCopy.name), index, totalJars)
            JarArchiver.extract(jarCopy, jarDecompDir)

            val dexFiles = findDexFiles(jarDecompDir)
            LogSink.detail("Prepare", "${jin.logicalName}: ${dexFiles.size} dex trong jar")

            when (input.mode) {
                // DEX_REDIVISION: decompile bình thường, không redivide ở đây.
                // User có thể chạy DexRedivisionScript sau để merge và redivide.
                else -> decompilePerDex(dexFiles, dexDecompDir, progress, index, totalJars)
            }

            slots.add(
                JarSlot(
                    logicalName = jin.logicalName,
                    originalFileName = jin.displayName,
                    jarDecompDir = jarDecompDir.absolutePath,
                    dexDecompDir = dexDecompDir.absolutePath
                )
            )
        }

        WorkSession(mode = input.mode, workDir = workDir.absolutePath, slots = slots).also {
            LogSink.section("Prepare", "sẵn sàng · ${slots.size} slot(s)")
        }
    }

    private fun findDexFiles(jarDecompDir: File): List<File> =
        jarDecompDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".dex", ignoreCase = true) }
            ?.sortedBy { naturalSortKey(it.name) }
            ?: emptyList()

    private fun naturalSortKey(name: String): String {
        // classes.dex < classes2.dex < classes10.dex
        val base = name.removeSuffix(".dex")
        val num = base.removePrefix("classes").ifEmpty { "1" }
        return num.padStart(6, '0')
    }

    private suspend fun decompilePerDex(
        dexFiles: List<File>,
        dexDecompDir: File,
        progress: (String, Int, Int) -> Unit,
        slotIndex: Int,
        totalJars: Int
    ) {
        val entries = linkedMapOf<String, DexEditedEntry>()
        dexFiles.forEachIndexed { idx, dex ->
            val dexName = dex.nameWithoutExtension
            val started = System.currentTimeMillis()
            progress(ctx.getString(R.string.build_decompile_fmt, dexName), slotIndex, totalJars)
            val loaded = runCatching { DexIo.load(dex) }.getOrElse {
                LogSink.e("Prepare", "Load dex ${dex.name} failed", it)
                entries[dexName] = DexEditedEntry(value = 0, version = DexIo.DEFAULT_DEX_VERSION)
                return@forEachIndexed
            }
            val outDir = FileIo.ensureDir(File(dexDecompDir, dexName))
            val count = Baksmali.decompile(loaded, outDir) { cur, tot ->
                progress(ctx.getString(R.string.build_decompile_progress_fmt, dexName, cur, tot), slotIndex, totalJars)
            }
            entries[dexName] = DexEditedEntry(value = 0, version = loaded.version)
            val ms = System.currentTimeMillis() - started
            LogSink.detail("Prepare", "baksmali $dexName · $count classes, v${loaded.version} (${ms}ms)")
        }
        DexEditedStore.write(dexDecompDir, entries)
    }

    private suspend fun decompileAndRedivide(
        dexFiles: List<File>,
        dexDecompDir: File,
        limit: Int,
        progress: (String, Int, Int) -> Unit,
        slotIndex: Int,
        totalJars: Int
    ) {
        // 1) Decompile tất cả dex vào cùng thư mục `classes/` (tạm — sẽ bị redivide sau).
        val merged = FileIo.ensureDir(File(dexDecompDir, "classes"))
        var highestVer = DexIo.DEFAULT_DEX_VERSION
        dexFiles.forEachIndexed { idx, dex ->
            val started = System.currentTimeMillis()
            progress(ctx.getString(R.string.build_decompile_fmt, dex.name), slotIndex, totalJars)
            val loaded = runCatching { DexIo.load(dex) }.getOrElse {
                LogSink.e("Prepare", "Load dex ${dex.name} failed", it); return@forEachIndexed
            }
            if (loaded.version > highestVer) highestVer = loaded.version
            val count = Baksmali.decompile(loaded, merged) { cur, tot ->
                progress(ctx.getString(R.string.build_merged_decompile_progress_fmt, dex.name, cur, tot), slotIndex, totalJars)
            }
            val ms = System.currentTimeMillis() - started
            LogSink.detail("Prepare", "baksmali (merge) ${dex.name} · $count classes (${ms}ms)")
        }

        // 2) Redivide merged classes/ → classes/, classes2/, ... theo limit.
        progress(ctx.getString(R.string.prepare_redivide_smali_fmt, limit), slotIndex, totalJars)
        val names = DexRedivision.redivide(dexDecompDir, "classes", limit) { stage, cur, total ->
            val label = when (stage) {
                "Parse smali" -> ctx.getString(R.string.prepare_redivision_parse_smali)
                "Phân phối dex" -> ctx.getString(R.string.prepare_redivision_allocate)
                "Move smali" -> ctx.getString(R.string.prepare_redivision_move)
                else -> stage
            }
            progress(ctx.getString(R.string.prepare_substage_fmt, label, cur, total), slotIndex, totalJars)
        }

        // 3) Ghi dex_edited.json với TẤT CẢ dex mới value=1 (vì chúng ta phải build lại tất cả).
        val entries = linkedMapOf<String, DexEditedEntry>()
        for (n in names) entries[n] = DexEditedEntry(value = 1, version = highestVer)
        DexEditedStore.write(dexDecompDir, entries)
        LogSink.detail("Prepare", "redivide → ${names.size} dex, v$highestVer (value=1)")
    }
}
