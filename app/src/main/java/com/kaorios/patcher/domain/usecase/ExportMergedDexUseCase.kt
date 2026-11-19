package com.kaorios.patcher.domain.usecase

import android.content.Context
import android.net.Uri
import com.kaorios.patcher.data.dex.Smali
import com.kaorios.patcher.data.log.LogSink
import com.kaorios.patcher.util.FileIo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Gom các file/thư mục smali đã chọn (list [sourceFiles] chứa cả file lẻ và folder) →
 * build 1 cây tạm theo **relative path tính từ `classesN/`** → compile thành 1 dex duy nhất →
 * ghi ra [outputUri].
 */
class ExportMergedDexUseCase(private val ctx: Context) {

    /** @return số file smali đã gom. */
    suspend operator fun invoke(sourceFiles: List<File>, outputUri: Uri): Int =
        withContext(Dispatchers.IO) {
            // 1) Gom tất cả .smali từ source (cả file đơn lẻ và walk descendants của folder).
            val smaliFiles = mutableListOf<File>()
            for (src in sourceFiles) {
                if (src.isFile && src.name.endsWith(".smali", ignoreCase = true)) {
                    smaliFiles.add(src)
                } else if (src.isDirectory) {
                    smaliFiles.addAll(
                        src.walkTopDown().filter { it.isFile && it.name.endsWith(".smali", ignoreCase = true) }
                    )
                }
            }
            if (smaliFiles.isEmpty()) error("Không có file .smali nào trong selection")

            // 2) Tạo thư mục tạm chứa bản sao theo cấu trúc relative-to-classesN.
            val tmpRoot = File.createTempFile("kaorios_export_", ".tmp", ctx.cacheDir).apply {
                delete(); mkdirs()
            }
            try {
                val usedRel = HashSet<String>()
                var copied = 0
                for (f in smaliFiles) {
                    val rel = relativeToClasses(f) ?: f.name
                    if (rel in usedRel) {
                        LogSink.w("ExportDex", "Bỏ qua duplicate class: $rel")
                        continue
                    }
                    usedRel.add(rel)
                    val dst = File(tmpRoot, rel)
                    dst.parentFile?.let(FileIo::ensureDir)
                    f.inputStream().use { ins -> dst.outputStream().use { out -> ins.copyTo(out) } }
                    copied++
                }
                LogSink.i("ExportDex", "Prepared $copied smali under ${tmpRoot.absolutePath}")

                // 3) Compile → bytes.
                val bytes = Smali.assemble(tmpRoot, dexVersion = 39)
                LogSink.i("ExportDex", "Assembled dex: ${bytes.size} bytes")

                // 4) Ghi qua SAF.
                ctx.contentResolver.openOutputStream(outputUri)?.use { it.write(bytes) }
                    ?: error("Không mở được OutputStream cho $outputUri")

                copied
            } finally {
                tmpRoot.deleteRecursively()
            }
        }

    /**
     * Đi ngược parent đến khi gặp 1 thư mục bắt đầu với "classes", lấy phần relative sau nó.
     * Ví dụ `.../framework_dex_decomp/classes2/android/foo/Bar.smali` → `android/foo/Bar.smali`.
     * Nếu không tìm thấy, trả `null` (caller sẽ fallback dùng `file.name`).
     */
    private fun relativeToClasses(smali: File): String? {
        val parts = mutableListOf(smali.name)
        var cur: File? = smali.parentFile
        while (cur != null) {
            if (cur.name.startsWith("classes", ignoreCase = true)) {
                return parts.reversed().joinToString("/")
            }
            parts.add(cur.name)
            cur = cur.parentFile
        }
        return null
    }
}
