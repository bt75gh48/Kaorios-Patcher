package com.kaorios.patcher.domain.actions.scripts

import android.content.Context
import com.kaorios.patcher.data.dex.DexRedivision
import com.kaorios.patcher.data.log.LogSink
import com.kaorios.patcher.data.workspace.DexEditedStore
import com.kaorios.patcher.domain.actions.ActionScript
import com.kaorios.patcher.domain.actions.ScriptOp
import com.kaorios.patcher.util.FileIo
import java.io.File

/**
 * Script "Dex Redivision" — phân phối lại các thư mục classes trong workspace.
 *
 * Chạy sau khi các script khác hoàn tất (hoặc chạy độc lập trong workspace đã có sẵn).
 *
 * Với mỗi slot (framework, services, hoặc input):
 * - Tìm tất cả thư mục `classes[N]/` trong `*_dex_decomp/`.
 * - Gộp tất cả smali vào `classes/` tạm thời (bên trong thư mục ẩn .rediv_merge/).
 * - Redivide lại thành các thư mục classes, classes2, ... theo limit.
 * - Xóa thư mục tạm và các thư mục classesN cũ.
 * - Đánh dấu các dex đã thay đổi value=1.
 *
 * Lưu ý: Script này chỉ hoạt động khi workspace có cấu trúc `*_dex_decomp/`.
 */
object DexRedivisionScript : ActionScript {
    override val id = "dex_redivision"
    override val displayName = "Dex Redivision"
    override val workingDirName = "dex_redivision"

    /** Số method/field tối đa trong mỗi dex (giới hạn dex cổ điển). */
    const val DEFAULT_LIMIT = 64_000

    /**
     * Operations rỗng — script này thực hiện redivision ở cấp độ thư mục,
     * không tác động lên method cụ thể nào.
     */
    override val operations: List<ScriptOp> = emptyList()

    /**
     * Thực hiện redivision cho tất cả slots trong workspace.
     */
    override suspend fun afterCommit(
        ctx: Context,
        workDir: File,
        roots: List<File>,
        progress: (String) -> Unit
    ) {
        val limit = readRedivisionLimit(workDir) ?: DEFAULT_LIMIT

        progress("Bắt đầu Dex Redivision (limit=$limit)…")

        var totalRedivided = 0
        for (root in roots) {
            progress("Đang phân tích ${root.name}…")

            // 1) Tìm tất cả thư mục classesN
            val classesDirs = root.listFiles()
                ?.filter { it.isDirectory && Regex("""^classes(\d*)$""").matches(it.name) }
                ?.sortedBy {
                    // Sắp xếp: classes trước, rồi classes2, classes3...
                    if (it.name == "classes") 0 else it.name.removePrefix("classes").toIntOrNull() ?: Int.MAX_VALUE
                }
                ?: emptyList()

            if (classesDirs.size <= 1) {
                LogSink.d("DexRedivision", "${root.name}: chỉ có ${classesDirs.size} thư mục classes — bỏ qua")
                continue
            }

            val totalFiles = classesDirs.sumOf { dir ->
                dir.walkTopDown().count { it.isFile && it.name.endsWith(".smali", ignoreCase = true) }
            }
            LogSink.i("DexRedivision", "${root.name}: ${classesDirs.size} thư mục, $totalFiles file smali")

            // 2) Tạo thư mục merge tạm thời
            val mergeDir = File(root, ".rediv_merge/classes").apply { deleteRecursively(); mkdirs() }

            // 3) Gộp tất cả smali vào mergeDir
            var merged = 0
            for (classesDir in classesDirs) {
                progress("Đang gộp ${classesDir.name}…")
                classesDir.walkTopDown()
                    .filter { it.isFile && it.name.endsWith(".smali", ignoreCase = true) }
                    .forEach { file ->
                        val rel = file.relativeTo(classesDir).path
                        val dest = File(mergeDir, rel)
                        dest.parentFile?.mkdirs()
                        file.copyTo(dest, overwrite = true)
                        merged++
                    }
            }
            LogSink.d("DexRedivision", "${root.name}: đã gộp $merged file smali")

            // 4) Xóa các thư mục classesN cũ (chỉ giữ lại mergeDir)
            for (classesDir in classesDirs) {
                FileIo.deleteRecursively(classesDir)
            }

            // 5) Thực hiện redivide trên mergeDir
            val names = DexRedivision.redivide(root, ".rediv_merge/classes", limit) { stage, cur, total ->
                progress("[$stage] ${root.name} ($cur/$total)")
            }

            // 6) Xóa thư mục merge tạm
            FileIo.deleteRecursively(File(root, ".rediv_merge"))

            // 7) Đánh dấu các dex đã thay đổi
            for (name in names) {
                DexEditedStore.setEditedFlag(root, name, 1)
            }

            totalRedivided += names.size
            LogSink.i("DexRedivision", "${root.name}: redivided → ${names.size} dex (${names.joinToString()})")
        }

        progress("Dex Redivision hoàn tất — $totalRedivided dex")
        LogSink.i("DexRedivision", "Hoàn tất: $totalRedivided dex trong ${roots.size} root(s)")
    }

    /**
     * Đọc limit từ file config trong workDir.
     */
    private fun readRedivisionLimit(workDir: File): Int? {
        val configFile = File(workDir, "redivision_config.json")
        if (!configFile.exists()) return null
        return runCatching {
            configFile.readText().trim().toIntOrNull()
        }.getOrNull()
    }

    /**
     * Lưu limit vào config file để afterCommit có thể đọc.
     */
    fun saveRedivisionLimit(workDir: File, limit: Int) {
        val configFile = File(workDir, "redivision_config.json")
        configFile.writeText(limit.toString())
        LogSink.d("DexRedivision", "Đã lưu limit=$limit vào config")
    }
}
