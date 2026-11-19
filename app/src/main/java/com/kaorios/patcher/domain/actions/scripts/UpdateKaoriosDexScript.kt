package com.kaorios.patcher.domain.actions.scripts

import android.content.Context
import com.kaorios.patcher.data.log.LogSink
import com.kaorios.patcher.data.workspace.DexEditedStore
import com.kaorios.patcher.domain.actions.ActionScript
import com.kaorios.patcher.domain.actions.ActionState
import com.kaorios.patcher.domain.actions.ClassesCleanup
import com.kaorios.patcher.domain.actions.DexDownloader
import com.kaorios.patcher.domain.actions.ScriptOp
import com.kaorios.patcher.domain.actions.UpdateKaoriosDexConfig
import com.kaorios.patcher.domain.usecase.ImportDexUseCase
import com.kaorios.patcher.util.FileIo
import java.io.File

/**
 * Script "Update Kaorios Dex" — chỉ chạy được trên framework đã vá KaoriosToolbox 2.0.3+.
 *
 * Luồng:
 * 1. Quét tất cả `classesN/` của mọi root, xóa các thư mục có tên **khớp chính xác** `kaorios`
 *    hoặc `kousei` (gỡ sạch dex KaoriosToolbox cũ trong workspace).
 * 2. Sau cleanup, classesN nào không còn smali nào → loại khỏi workspace + dex_edited.json
 *    (tránh build dex rỗng).
 * 3. Tải dex KaoriosToolbox version mới (tag user nhập, đã validate trước) từ GitHub release
 *    và import vào `framework_dex_decomp` như 1 classesN mới.
 * 4. Lưu tag dùng vào ActionState để Build module pick đúng zip module.
 *
 * Không patch method nào → [operations] rỗng. Toàn bộ công việc thực hiện trong [afterCommit].
 */
object UpdateKaoriosDexScript : ActionScript {
    override val id = ActionState.SCRIPT_ID_UPDATE_KAORIOS_DEX
    override val displayName = "Update Kaorios Dex"
    override val workingDirName = "update_kaorios_dex"

    // Cùng repo với KaoriosToolbox.
    const val REPO_OWNER = KaoriosToolbox2036Script.REPO_OWNER
    const val REPO_NAME = KaoriosToolbox2036Script.REPO_NAME

    /** Tên thư mục cần xóa (exact, case-sensitive) trong tất cả classesN. */
    private val FORBIDDEN_FOLDER_NAMES = setOf("kaorios", "kousei")

    override val operations: List<ScriptOp> = emptyList()

    override suspend fun afterCommit(
        ctx: Context,
        workDir: File,
        roots: List<File>,
        progress: (String) -> Unit
    ) {
        // === Step 1: xóa folder kaorios/kousei ở mọi classesN ===
        progress("Xóa các thư mục kaorios/kousei cũ…")
        var totalDeleted = 0
        for (root in roots) {
            val classesDirs = root.listFiles()
                ?.filter { it.isDirectory && it.name.startsWith("classes", ignoreCase = true) }
                ?: continue
            for (cd in classesDirs) {
                val victims = cd.walkTopDown()
                    .filter { it.isDirectory && it.name in FORBIDDEN_FOLDER_NAMES }
                    .toList()
                if (victims.isEmpty()) continue
                victims.forEach { v ->
                    FileIo.deleteRecursively(v)
                    totalDeleted++
                    LogSink.d("UpdateKaoriosDex", "deleted folder: ${v.absolutePath}")
                }
                // Dex chứa folder bị xóa → mark dirty.
                DexEditedStore.setEditedFlag(root, cd.name, 1)
            }
        }
        progress("Đã xóa $totalDeleted thư mục")
        LogSink.detail("UpdateKaoriosDex", "deleted $totalDeleted folder(s) named kaorios/kousei")

        // === Step 2: prune classesN rỗng (không còn smali) ===
        progress("Dọn classesN rỗng…")
        val pruned = ClassesCleanup.pruneEmpty(roots)
        if (pruned > 0) {
            progress("Đã loại $pruned classesN rỗng khỏi workspace")
            LogSink.detail("UpdateKaoriosDex", "pruned $pruned empty classesN dir(s)")
        }

        // === Step 3: tải dex mới + import vào framework ===
        val frameworkRoot = roots.firstOrNull { it.name.startsWith("framework") }
        if (frameworkRoot == null) {
            LogSink.w("UpdateKaoriosDex", "không có framework root — bỏ qua import")
            return
        }
        val tag = UpdateKaoriosDexConfig.currentTag
        val cacheKey = "kaoriostoolbox_${tag.replace('/', '_')}"
        progress("Tải dex KaoriosToolbox $tag từ GitHub…")
        LogSink.detail("UpdateKaoriosDex", "dex · $REPO_OWNER/$REPO_NAME @$tag")

        val dexFile = try {
            DexDownloader.downloadReleaseDex(
                ctx = ctx,
                repoOwner = REPO_OWNER,
                repoName = REPO_NAME,
                tag = tag,
                cacheKey = cacheKey
            ) { bytes, total ->
                val pct = if (total > 0) "${(bytes * 100 / total)}%" else "${bytes / 1024}KB"
                progress("Tải dex: $pct")
            }
        } catch (t: Throwable) {
            LogSink.e("UpdateKaoriosDex", "download FAIL: ${t.message}", t)
            throw IllegalStateException(
                "Không tải được dex từ release '$tag' của $REPO_OWNER/$REPO_NAME. " +
                    "Nguyên nhân: ${t.message}",
                t
            )
        }

        progress("Import dex vào ${frameworkRoot.name}…")
        val imported = ImportDexUseCase(ctx).invoke(frameworkRoot, dexFile, ownsFile = false)
        LogSink.detail("UpdateKaoriosDex", "import OK → $imported")

        // Lưu tag vào ActionState để Build module sau đó pick đúng zip.
        ActionState.setScriptTag(workDir, ActionState.SCRIPT_ID_UPDATE_KAORIOS_DEX, tag)
        progress("Update Kaorios Dex xong → $imported (tag $tag đã ghi nhận)")
    }
}
