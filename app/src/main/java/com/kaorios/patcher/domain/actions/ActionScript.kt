package com.kaorios.patcher.domain.actions

import android.content.Context
import java.io.File

/**
 * Định nghĩa 1 script và các operations bên trong.
 *
 * Script có thể khai báo thêm bước `afterCommit` — chạy SAU khi tất cả operations đã được commit
 * (file gốc đã được thay thế thành công). Bước này dùng cho các hành động không phù hợp với
 * snapshot/rollback: download asset từ internet, xóa folder theo tên, v.v.
 */
interface ActionScript {
    val id: String
    val displayName: String
    /** Tên thư mục snapshot dưới workDir (vd `kaoriostoolbox_2036`, `remove_hooks`). */
    val workingDirName: String
    val operations: List<ScriptOp>

    /**
     * Chạy sau khi commit thành công. Default no-op. Lỗi trong đây KHÔNG rollback
     * (vì file gốc đã thay thế rồi); chỉ log + ném ngược nếu muốn UI báo.
     */
    suspend fun afterCommit(
        ctx: Context,
        workDir: File,
        roots: List<File>,
        progress: (String) -> Unit
    ) = Unit
}

data class ScriptOp(
    val key: String,
    val classDescriptor: String,
    val methodSignature: String,
    val checkBannedWords: Boolean,
    /**
     * Nếu khác null: tìm method bằng dòng chứa chuỗi này (vd neo `startOtherServices`),
     * không dùng [methodSignature] để locate — hợp ROM dùng `main` thay `run`.
     */
    val methodAnchorLineContains: String? = null,
    val transform: (SmaliEngine.MutableMethod) -> Unit
)

enum class ScriptPriority(val order: Int) { REMOVE_HOOKS(0), TOOLBOX(1), UPDATE(2), REBUILD(3) }

enum class ScriptGroup { REMOVE, TOOLBOX, UPDATE, REBUILD }
