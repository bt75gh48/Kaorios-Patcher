package com.kaorios.patcher.domain.usecase

import android.content.Context
import android.net.Uri
import com.kaorios.patcher.data.dex.Smali
import com.kaorios.patcher.data.jar.JarArchiver
import com.kaorios.patcher.data.jar.ZipStored
import com.kaorios.patcher.data.log.LogSink
import com.kaorios.patcher.data.workspace.DexEditedStore
import com.kaorios.patcher.data.workspace.Workspace
import com.kaorios.patcher.domain.actions.ActionState
import com.kaorios.patcher.domain.actions.DexDownloader
import com.kaorios.patcher.R
import com.kaorios.patcher.domain.actions.scripts.KaoriosToolbox2036Script
import com.kaorios.patcher.domain.model.WorkMode
import com.kaorios.patcher.util.FileIo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Build lại jar từ workspace.
 *
 * 2 kind:
 * - `BuildKind.JAR`: luồng cũ — compile dex → pack jar → export ra SAF.
 * - `BuildKind.MODULE` (chỉ KAORIOS_PATCHER): sau khi build jar xong, tải module .zip từ GitHub
 *   release, replace `system/framework/framework.jar` + `system/framework/services.jar` bên trong
 *   module zip với file đã build, ghi lại zip có hậu tố thời gian, export ra SAF.
 */
class BuildUseCase(private val ctx: Context) {

    enum class BuildKind { JAR, MODULE }

    data class Result(
        val outputs: List<File>,
        val compiledDex: Int,
        val kind: BuildKind,
        val moduleTag: String? = null
    )

    suspend fun invoke(
        workDir: File,
        mode: WorkMode,
        kind: BuildKind,
        exportTreeUri: Uri?,
        progress: (stage: String) -> Unit = {}
    ): Result = withContext(Dispatchers.IO) {
        val t0 = System.currentTimeMillis()
        LogSink.section("Build", "bắt đầu · $kind · $mode")
        LogSink.detail("Build", workDir.absolutePath)

        val outputsDir = Workspace.outputDir(workDir)
        FileIo.deleteRecursively(outputsDir)
        FileIo.ensureDir(outputsDir)

        val slotDirs = workDir.listFiles()
            ?.filter { it.isDirectory && it.name.endsWith("_jar_decomp") }
            ?.sortedBy { it.name }
            ?: emptyList()
        LogSink.detail("Build", "slots: ${slotDirs.joinToString { it.name }}")

        var compiled = 0
        val producedJars = mutableListOf<File>()
        slotDirs.forEach { jarDecompDir ->
            val logicalName = jarDecompDir.name.removeSuffix("_jar_decomp")
            val dexDecompDir = File(workDir, "${logicalName}_dex_decomp")
            LogSink.section("Build", "slot · $logicalName")
            progress(ctx.getString(R.string.build_progress_logical_fmt, logicalName))
            val slotCompiled = try {
                buildPerDex(dexDecompDir, jarDecompDir, progress)
            } catch (t: Throwable) {
                LogSink.e("Build", "slot $logicalName FAILED", t); throw t
            }
            compiled += slotCompiled

            val outJar = File(outputsDir, "$logicalName.jar")
            progress(ctx.getString(R.string.build_progress_pack_jar_fmt, logicalName))
            val entries = JarArchiver.pack(jarDecompDir, outJar)
            LogSink.detail("Build", "pack jar · ${outJar.name} (${outJar.length()}B, $entries entries)")
            producedJars.add(outJar)
        }

        if (kind == BuildKind.MODULE && mode != WorkMode.KAORIOS_PATCHER) {
            throw IllegalStateException(ctx.getString(R.string.build_module_only_kaorios))
        }

        val finalOutputs: List<File>
        val moduleTag: String?
        if (kind == BuildKind.JAR) {
            finalOutputs = producedJars
            moduleTag = null
        } else {
            progress(ctx.getString(R.string.build_progress_resolve_module_tag))
            val tag = ActionState.preferredModuleTag(workDir) // null = latest
            LogSink.detail("Build", "module zip tag: ${tag ?: "(latest)"}")

            progress(ctx.getString(R.string.build_progress_download_module_zip))
            val moduleZip = downloadModuleZip(tag, progress)
            progress(ctx.getString(R.string.build_progress_replace_module_jars))
            val outZip = rebuildModuleZip(outputsDir, moduleZip, producedJars, tag)
            LogSink.detail("Build", "module zip · ${outZip.name} (${outZip.length()}B)")
            finalOutputs = listOf(outZip)
            moduleTag = tag
        }

        if (exportTreeUri != null) {
            progress(ctx.getString(R.string.build_progress_export_storage))
            try {
                SafUtils.exportFilesToTree(ctx, exportTreeUri, finalOutputs)
                LogSink.detail("Build", "export · ${finalOutputs.size} file(s) → SAF")
            } catch (t: Throwable) {
                LogSink.e("Build", "Export SAF failed", t); throw t
            }
        }

        val dt = System.currentTimeMillis() - t0
        LogSink.section("Build", "xong · $compiled dex · ${finalOutputs.size} output · ${dt}ms")
        Result(finalOutputs, compiled, kind, moduleTag)
    }

    private suspend fun downloadModuleZip(tag: String?, progress: (String) -> Unit): File {
        val cacheKey = "module_${tag ?: "latest"}"
        return DexDownloader.downloadReleaseZip(
            ctx = ctx,
            repoOwner = KaoriosToolbox2036Script.REPO_OWNER,
            repoName = KaoriosToolbox2036Script.REPO_NAME,
            tag = tag,
            cacheKey = cacheKey
        ) { bytes, total ->
            val pct = if (total > 0) "${bytes * 100 / total}%" else "${bytes / 1024}KB"
            progress(ctx.getString(R.string.build_progress_download_module_pct_fmt, pct))
        }
    }

    /**
     * Rebuild module zip: copy all entries, replace `system/framework/<name>.jar` với file build mới.
     */
    private fun rebuildModuleZip(
        outputsDir: File,
        srcZip: File,
        newJars: List<File>,
        tag: String?
    ): File {
        val replaceMap = newJars.associateBy { "system/framework/${it.name}" }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outName = buildString {
            append(srcZip.nameWithoutExtension.ifEmpty { "kaorios_module" })
            if (!tag.isNullOrBlank()) append('_').append(tag.replace('/', '_'))
            append('_').append(ts).append(".zip")
        }
        val outZip = File(outputsDir, outName)
        val touched = mutableSetOf<String>()

        ZipInputStream(FileInputStream(srcZip)).use { zin ->
            ZipOutputStream(FileOutputStream(outZip)).use { zout ->
                while (true) {
                    val entry = zin.nextEntry ?: break
                    val name = entry.name.replace('\\', '/')
                    if (entry.isDirectory) {
                        ZipStored.putStoredDirectory(zout, name)
                        zin.closeEntry()
                        continue
                    }
                    val override = replaceMap[name]
                    if (override != null) {
                        val t = if (entry.time != -1L) entry.time else override.lastModified()
                        ZipStored.putStoredFile(zout, name, override, t)
                        touched.add(name)
                        LogSink.detail("Build", "zip replace · $name ← ${override.name} (${override.length()}B)")
                    } else {
                        val data = zin.readBytes()
                        val t = if (entry.time != -1L) entry.time else System.currentTimeMillis()
                        ZipStored.putStoredBytes(zout, name, data, t)
                    }
                    zin.closeEntry()
                }
                // Thêm bất kỳ jar new nào chưa có trong zip gốc (phòng hờ).
                for ((name, file) in replaceMap) {
                    if (name !in touched) {
                        ZipStored.putStoredFile(zout, name, file)
                        LogSink.w("Build", "zip inject · $name (${file.length()}B)")
                    }
                }
            }
        }
        return outZip
    }

    // ---------------- jar build (reused) ----------------

    private fun buildPerDex(
        dexDecompDir: File,
        jarDecompDir: File,
        progress: (String) -> Unit
    ): Int {
        val map = DexEditedStore.read(dexDecompDir)
        if (map.isEmpty()) {
            LogSink.w("Build", "dex_edited.json rỗng · ${dexDecompDir.name}"); return 0
        }
        val toBuild = map.filterValues { it.value == 1 }
        LogSink.detail("Build", "compile queue · ${toBuild.size} dex: ${toBuild.keys.joinToString()}")

        // Xoá các .dex trong jar_decomp KHÔNG có entry trong dex_edited (dex dư sau redivide).
        // Với KAORIOS/OTHER_JAR thông thường, tất cả dex có entry → no-op.
        // Với DEX_REDIVISION (sau redivide): dex_edited chỉ có N dex mới, các .dex thừa bị xoá.
        val expectedFiles = map.keys.map { "$it.dex" }.toSet()
        jarDecompDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".dex") && it.name !in expectedFiles }
            ?.forEach {
                it.delete()
                LogSink.d("Build", "removed stale dex: ${it.name}")
            }
        var compiled = 0
        toBuild.forEach { (dexName, entry) ->
            val smaliRoot = File(dexDecompDir, dexName)
            if (!smaliRoot.isDirectory) {
                LogSink.w("Build", "SKIP $dexName — không có thư mục smali"); return@forEach
            }
            progress(ctx.getString(R.string.build_progress_compile_dex_fmt, dexName, entry.version))
            val t0 = System.currentTimeMillis()
            try {
                val bytes = Smali.assemble(smaliRoot, entry.version)
                val outDex = File(jarDecompDir, "$dexName.dex")
                outDex.writeBytes(bytes)
                LogSink.detail("Build", "smali OK · $dexName (${bytes.size}B, ${System.currentTimeMillis() - t0}ms)")
                compiled++
            } catch (t: Throwable) {
                LogSink.e("Build", "smali FAIL · $dexName: ${t.message}", t)
                throw IllegalStateException(
                    ctx.getString(R.string.build_compile_failed_fmt, dexName, t.message ?: "?"),
                    t
                )
            }
        }
        return compiled
    }

    private fun buildMerged(
        dexDecompDir: File,
        jarDecompDir: File,
        progress: (String) -> Unit
    ): Int {
        val smaliRoot = File(dexDecompDir, "classes")
        if (!smaliRoot.isDirectory) return 0
        progress(ctx.getString(R.string.build_progress_compile_merged))
        val t0 = System.currentTimeMillis()
        try {
            val bytes = Smali.assemble(smaliRoot, 39)
            val outDex = File(jarDecompDir, "classes.dex")
            outDex.writeBytes(bytes)
            LogSink.detail("Build", "smali OK · merged (${bytes.size}B, ${System.currentTimeMillis() - t0}ms)")
            return 1
        } catch (t: Throwable) {
            LogSink.e("Build", "smali FAIL · merged: ${t.message}", t)
            throw IllegalStateException(ctx.getString(R.string.build_compile_merged_failed_fmt, t.message ?: "?"), t)
        }
    }
}
