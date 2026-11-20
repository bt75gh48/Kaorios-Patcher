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
    /**
     * Nếu true: file mục tiêu không có trong workspace thì bỏ qua op thay vì rollback.
     * Dùng cho bộ hook framework/services — ROM có thể chỉ có một trong hai root.
     */
    val optional: Boolean = false,
    val patchMode: PatchMode = PatchMode.ORIGINAL,
    val fileTransform: ((String) -> String)? = null,
    val transform: (SmaliEngine.MutableMethod) -> Unit = {}
)

/**
 * Thứ tự thực thi. Giá trị càng nhỏ chạy càng sớm.
 *
 * `TOOLBOX` đứng trước `REMOVE_*` vì KaoriosToolbox import dex và patch caller; các
 * script remove/patch lẻ chạy sau trên cùng workspace.
 */
enum class ScriptPriority(val order: Int) {
    TOOLBOX(0),
    REMOVE_HOOKS(1),
    REMOVE_INVOKE_CUSTOM(2),
    REMOVE_BUILD_FLAG(3),
    REBUILD(4),
    UPDATE(5),
    HIDE_DEV_OPTIONS(6),
    HIDE_APP(7),
    SPOOF_INSTALLED_SOURCE(8),
    DISABLE_SECURE_FLAG(9)
}

enum class ScriptGroup { REMOVE, TOOLBOX, UPDATE, REBUILD }
