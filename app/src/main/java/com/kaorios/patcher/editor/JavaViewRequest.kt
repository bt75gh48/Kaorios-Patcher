package com.kaorios.patcher.editor

import java.io.File

/**
 * Dữ liệu trung gian giữa SmaliEditor và JavaViewScreen: danh sách file smali cần compile
 * cùng primary class user đã chọn. Dùng singleton vì không muốn serialize List<File> vào nav arg.
 */
object JavaViewRequest {

    data class Payload(
        val files: List<File>,
        val primary: File,
        val dexVersion: Int
    )

    @Volatile
    var pending: Payload? = null

    /** Lấy và clear trong 1 bước. Trả null nếu chưa có. */
    @Synchronized
    fun consume(): Payload? {
        val p = pending
        pending = null
        return p
    }
}
