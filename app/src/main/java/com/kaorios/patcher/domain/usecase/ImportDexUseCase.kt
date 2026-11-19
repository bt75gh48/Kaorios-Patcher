package com.kaorios.patcher.domain.usecase

import android.content.Context
import android.net.Uri
import com.kaorios.patcher.data.dex.Baksmali
import com.kaorios.patcher.data.dex.DexIo
import com.kaorios.patcher.data.log.LogSink
import com.kaorios.patcher.data.workspace.DexEditedStore
import com.kaorios.patcher.domain.model.DexEditedEntry
import com.kaorios.patcher.util.FileIo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Import 1 file .dex từ SAF vào workspace:
 *   1. Copy uri → file tạm.
 *   2. Xác định tên `classesN` tiếp theo (chưa tồn tại) trong [targetRoot].
 *   3. Baksmali → `targetRoot/<classesN>/`.
 *   4. Cập nhật `dex_edited.json`: thêm entry với `value = 1` để BuildUseCase compile classesN
 *      này (dex mới chưa có trong jar_decomp, bắt buộc phải build mới xuất hiện khi pack).
 *
 * @return tên classesN đã tạo.
 */
class ImportDexUseCase(private val ctx: Context) {

    /** Import từ SAF URI — copy uri vào temp trước. */
    suspend operator fun invoke(targetRoot: File, uri: Uri): String = withContext(Dispatchers.IO) {
        val tmp = File.createTempFile("kaorios_import_", ".dex", ctx.cacheDir)
        try {
            FileIo.copyUriToFile(ctx.contentResolver, uri, tmp)
            LogSink.i("ImportDex", "Copied URI ${uri} -> ${tmp.absolutePath} (${tmp.length()}B)")
            invoke(targetRoot, tmp, /*ownsFile=*/true)
        } finally {
            if (tmp.exists()) tmp.delete()
        }
    }

    /**
     * Import từ 1 file .dex có sẵn trên filesystem (đã cache trước).
     * Nếu [ownsFile] = true, caller không cần xóa (use case tự delete sau decompile).
     */
    suspend operator fun invoke(
        targetRoot: File,
        dexFile: File,
        ownsFile: Boolean = false
    ): String = withContext(Dispatchers.IO) {
        FileIo.ensureDir(targetRoot)
        LogSink.i("ImportDex", "Importing ${dexFile.absolutePath} into ${targetRoot.name}")
        try {
            val loaded = DexIo.load(dexFile)
            val nextName = nextClassesName(targetRoot)
            val outDir = FileIo.ensureDir(File(targetRoot, nextName))
            LogSink.i("ImportDex", "Baksmali -> ${outDir.absolutePath} (v${loaded.version})")

            val count = Baksmali.decompile(loaded, outDir)
            LogSink.i("ImportDex", "Extracted $count classes into $nextName")

            val cur = DexEditedStore.read(targetRoot)
            // value = 1 → BuildUseCase.buildPerDex sẽ compile classesN này và ghi vào jar_decomp.
            // Nếu để value = 0, build sẽ bỏ qua và classesN hoàn toàn biến mất khỏi jar output.
            cur[nextName] = DexEditedEntry(value = 1, version = loaded.version)
            DexEditedStore.write(targetRoot, cur)
            LogSink.i("ImportDex", "Marked $nextName as edited (value=1, v${loaded.version})")
            nextName
        } finally {
            if (ownsFile && dexFile.exists()) dexFile.delete()
        }
    }

    /** classes, classes2, classes3, … — trả về tên chưa tồn tại. */
    private fun nextClassesName(root: File): String {
        val existing = root.listFiles()
            ?.filter { it.isDirectory && it.name.startsWith("classes") }
            ?.map { it.name }
            ?.toSet()
            ?: emptySet()
        if ("classes" !in existing) return "classes"
        var i = 2
        while ("classes$i" in existing) i++
        return "classes$i"
    }
}
