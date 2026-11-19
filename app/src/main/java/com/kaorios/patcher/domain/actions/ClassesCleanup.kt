package com.kaorios.patcher.domain.actions

import com.kaorios.patcher.data.log.LogSink
import com.kaorios.patcher.data.workspace.DexEditedStore
import com.kaorios.patcher.util.FileIo
import java.io.File

/**
 * Sau khi xóa hàng loạt folder smali (vd hook removal hoặc kaorios update):
 * nếu 1 thư mục `classesN/` không còn bất kỳ file `.smali` nào → xóa luôn folder
 * + xóa entry trong `dex_edited.json` để bước build không cố compile dex rỗng
 * (smali assemble sẽ throw nếu không có file nào).
 */
object ClassesCleanup {

    private const val TAG = "ClassesCleanup"

    /**
     * Quét tất cả `classesN/` của mọi root. Folder không có file `.smali` nào → drop.
     * Trả về số folder bị xoá.
     */
    fun pruneEmpty(roots: List<File>): Int {
        var count = 0
        for (root in roots) {
            val classesDirs = root.listFiles()
                ?.filter { it.isDirectory && it.name.startsWith("classes", ignoreCase = true) }
                ?: continue
            for (cd in classesDirs) {
                val hasSmali = cd.walkTopDown().any {
                    it.isFile && it.name.endsWith(".smali", ignoreCase = true)
                }
                if (hasSmali) continue
                LogSink.w(TAG, "empty after cleanup → drop: ${cd.absolutePath}")
                FileIo.deleteRecursively(cd)
                DexEditedStore.removeEntry(root, cd.name)
                count++
            }
        }
        return count
    }
}
