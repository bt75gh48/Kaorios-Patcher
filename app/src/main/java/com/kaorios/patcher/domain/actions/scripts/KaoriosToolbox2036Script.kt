package com.kaorios.patcher.domain.actions.scripts

import android.content.Context
import com.kaorios.patcher.data.log.LogSink
import com.kaorios.patcher.domain.actions.ActionScript
import com.kaorios.patcher.domain.actions.ActionState
import com.kaorios.patcher.domain.actions.DexDownloader
import com.kaorios.patcher.domain.actions.PatchMode
import com.kaorios.patcher.domain.actions.ScriptOp
import com.kaorios.patcher.domain.actions.SmaliEngine
import com.kaorios.patcher.domain.actions.Toolbox2036Config
import com.kaorios.patcher.domain.actions.smali.PayloadAbiVerifier
import com.kaorios.patcher.domain.actions.smali.TargetPatcher
import com.kaorios.patcher.domain.actions.smali.TargetStatus
import com.kaorios.patcher.domain.actions.smali.TreeVerifier
import com.kaorios.patcher.domain.usecase.ImportDexUseCase
import java.io.File

/**
 * KaoriosToolbox 2.0.3.6 — chèn các hook gọi sang `Landroid/security/kaorios/KaoriosHook;`.
 *
 * Sau khi commit thành công, `afterCommit` sẽ download file dex KaoriosToolbox 2.0.3.6 từ GitHub
 * release và import vào framework_dex_decomp như 1 classesN mới.
 *
 * Để thay đổi version: cập nhật 3 hằng số dưới — tag + asset name phải khớp với release bạn upload.
 * URL format: https://github.com/<OWNER>/<REPO>/releases/download/<TAG>/<ASSET>
 */
object KaoriosToolbox2036Script : ActionScript {
    override val id = "kaoriostoolbox_203"
    override val displayName = "KaoriosToolbox 2.0.3+"
    override val workingDirName = "kaoriostoolbox_203"

    // === Cấu hình GitHub release ===
    // Mỗi release chỉ upload 1 file .dex + 1 file .zip (tên tùy ý) — app dò theo extension.
    // Tag dùng runtime từ Toolbox2036Config (user xác thực trước khi chạy), default "V2.0.3".
    const val REPO_OWNER = "Wuang26"
    const val REPO_NAME = "Kaorios-Toolbox"

    override val operations: List<ScriptOp> = listOf(
        hookOp("framework/ActivityThread", "ActivityThread.smali"),
        hookOp("services/ComputerEngine", "ComputerEngine.smali"),
        hookOp("services/SystemServer", "SystemServer.smali"),
        hookOp("framework/Instrumentation", "Instrumentation.smali"),
        hookOp("framework/ApplicationPackageManager", "ApplicationPackageManager.smali"),
        hookOp("framework/Settings\$NameValueCache", "Settings\$NameValueCache.smali"),
        hookOp("framework/AndroidKeyStoreSpi", "AndroidKeyStoreSpi.smali"),
        hookOp("framework/AndroidKeyStoreKeyPairGeneratorSpi", "AndroidKeyStoreKeyPairGeneratorSpi.smali")
    )

    /**
     * Mỗi op patch trọn 1 file qua [TargetPatcher] (port của `apply_target_patch`).
     * `optional = true`: ROM có thể chỉ có framework hoặc chỉ có services — thiếu file
     * thì bỏ qua op thay vì rollback cả script.
     */
    private fun hookOp(key: String, filename: String): ScriptOp {
        val owner = TargetPatcher.TARGETS.getValue(filename)
        return ScriptOp(
            key = key,
            classDescriptor = owner,
            methodSignature = "",
            checkBannedWords = false,
            optional = true,
            patchMode = PatchMode.ORIGINAL,
            fileTransform = { content ->
                val result = TargetPatcher.apply(filename, content, update = false)
                when (result.status) {
                    TargetStatus.PATCHED, TargetStatus.ALREADY_PATCHED -> result.content
                    TargetStatus.NOT_TARGET -> error("$filename không nằm trong danh sách mục tiêu patch")
                    else -> error("$filename: ${result.status} — ${result.error}")
                }
            }
        )
    }

    /**
     * Sau khi commit OK: tải dex KaoriosToolbox 2.0.3.6 từ GitHub release và import vào
     * `framework_dex_decomp` như 1 classesN mới. Dex đã cache được dùng lại, không tải lại.
     */
    override suspend fun afterCommit(
        ctx: Context,
        workDir: File,
        roots: List<File>,
        progress: (String) -> Unit
    ) {
        val frameworkRoot = roots.firstOrNull { it.name.startsWith("framework") }
        if (frameworkRoot == null) {
            LogSink.w("Actions", "Toolbox dex: không có framework root — bỏ qua import")
            return
        }
        val tag = Toolbox2036Config.currentTag
        val cacheKey = "kaoriostoolbox_${tag.replace('/', '_')}"
        progress("Tải dex Toolbox $tag từ GitHub…")
        LogSink.detail("Actions", "Toolbox dex · $REPO_OWNER/$REPO_NAME @$tag")

        val dexFile = try {
            DexDownloader.downloadReleaseDex(
                ctx = ctx,
                repoOwner = REPO_OWNER,
                repoName = REPO_NAME,
                tag = tag,
                cacheKey = cacheKey
            ) { bytes, total ->
                val pct = if (total > 0) "${(bytes * 100 / total)}%" else "${bytes / 1024}KB"
                progress("Tải Toolbox dex: $pct")
            }
        } catch (t: Throwable) {
            LogSink.e("Actions", "Toolbox dex download FAIL: ${t.message}", t)
            throw IllegalStateException(
                "Không tải được dex Toolbox từ release '$tag' của $REPO_OWNER/$REPO_NAME. " +
                    "Nguyên nhân: ${t.message}",
                t
            )
        }

        progress("Import Toolbox dex vào ${frameworkRoot.name}…")
        val imported = ImportDexUseCase(ctx).invoke(frameworkRoot, dexFile, ownsFile = false)
        LogSink.detail("Actions", "Toolbox dex import OK → $imported")

        // Port của `_verify_payload_calls` + `run_directory_verifiers`: chạy SAU khi import
        // vì payload dex chỉ có mặt ở bước này. Lỗi ở đây không rollback (file gốc đã commit),
        // chỉ ném ngược để ActionRunner đánh dấu script FAIL.
        val payloadRoot = File(frameworkRoot, imported)
        val (staged, stagedNames) = stageCallers(roots)
        try {
            PayloadAbiVerifier.verifyCalls(staged, payloadRoot)
            LogSink.detail("Actions", "payload ABI PASS · ${staged.walkTopDown().count { it.isFile }} caller(s)")

            // Cổng giống `run_directory_verifiers`: chỉ chạy verifier khi file mục tiêu
            // có mặt trong tập đã stage. Không dò theo đường dẫn phẳng — file được stage
            // dưới `<classesN>/<internal path>` nên đường dẫn phẳng luôn sai.
            if ("ActivityThread.smali" in stagedNames) {
                TreeVerifier.verifyActivityThread(staged)
                LogSink.detail("Actions", "framework verifier PASS (ActivityThread)")
            }
            if ("ComputerEngine.smali" in stagedNames) {
                TreeVerifier.verifyServices(staged)
                LogSink.detail("Actions", "services verifier PASS (ComputerEngine)")
            }
            if ("SystemServer.smali" in stagedNames) {
                TreeVerifier.verifySystemServer(staged)
                LogSink.detail("Actions", "systemserver verifier PASS")
            }
        } finally {
            staged.deleteRecursively()
        }

        // Lưu tag vào ActionState — Build module sẽ dùng để chọn đúng module zip.
        ActionState.setScriptTag(workDir, ActionState.SCRIPT_ID_TOOLBOX_2036, tag)
        progress("Import Toolbox dex xong → $imported (tag $tag đã ghi nhận)")
    }

    /**
     * Gom các file mục tiêu đã patch vào một thư mục tạm, giữ nguyên relative path —
     * tương ứng `staged_root` của `process_files` bên Python. Nhờ vậy verifier chỉ nhìn
     * thấy caller, không quét lẫn payload vừa import.
     *
     * Trả kèm tập tên file đã stage — đây là `processed_filenames` mà
     * `run_directory_verifiers` dùng làm cổng, không phải đường dẫn phẳng.
     */
    private fun stageCallers(roots: List<File>): Pair<File, Set<String>> {
        val staged = kotlin.io.path.createTempDirectory("kaorios-preflight-").toFile()
        val names = mutableSetOf<String>()
        for ((filename, desc) in TargetPatcher.TARGETS) {
            val source = SmaliEngine.findAllForClass(roots, desc).firstOrNull() ?: continue
            val root = roots.firstOrNull { source.startsWith(it) } ?: roots.first()
            val rel = source.relativeTo(root).path
            val dest = File(staged, rel).apply { parentFile?.mkdirs() }
            source.copyTo(dest, overwrite = true)
            names += filename
        }
        return staged to names
    }
}
