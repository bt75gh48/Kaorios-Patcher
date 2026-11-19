package com.kaorios.patcher.domain.actions.scripts

import android.content.Context
import com.kaorios.patcher.data.log.LogSink
import com.kaorios.patcher.data.workspace.DexEditedStore
import com.kaorios.patcher.domain.actions.ActionScript
import com.kaorios.patcher.domain.actions.ClassesCleanup
import com.kaorios.patcher.domain.actions.ScriptOp
import com.kaorios.patcher.domain.actions.SmaliEngine
import com.kaorios.patcher.util.FileIo
import java.io.File

/**
 * Script "Remove Aosp/Custom rom Hooks" — xoá các hook có sẵn trong ROM nguồn, giúp các script
 * khác không bị block bởi check từ cấm.
 *
 * Không check banned keywords vì mục đích chính là GỠ hook.
 */
object RemoveHooksScript : ActionScript {
    override val id = "remove_hooks"
    override val displayName = "Remove Aosp/Custom rom Hooks"
    override val workingDirName = "remove_hooks"

    override val operations: List<ScriptOp> = listOf(
        // Op 1: AndroidKeyStoreSpi.engineGetCertificateChain — xoá block từ sau .registers đến invoke-direct getKeyMetadata
        ScriptOp(
            key = "framework/AndroidKeyStoreSpi.engineGetCertificateChain#headHook",
            classDescriptor = "Landroid/security/keystore2/AndroidKeyStoreSpi;",
            methodSignature = "engineGetCertificateChain(Ljava/lang/String;)[Ljava/security/cert/Certificate;",
            checkBannedWords = false
        ) { m ->
            SmaliEngine.deleteLinesBetween(
                method = m,
                afterContaining = null, // default: sau .registers
                beforeContaining = "Landroid/security/keystore2/AndroidKeyStoreSpi;->getKeyMetadata(Ljava/lang/String;)Landroid/system/keystore2/KeyEntryResponse;"
            )
        },

        // Op 2: AndroidKeyStoreSpi.engineGetCertificateChain — xoá block giữa aput-object (đỉnh chain) và return-object.
        // AOSP: const/4 + aput sát; ROM Pixel/custom: block dài trước getKeyMetadata, aput cuối + return không kèm const/4.
        // Xóa: dùng SmaliEngine (tight + lỏng + khối KaoriosHook) — tránh xoá aput trong vòng lặp.
        ScriptOp(
            key = "framework/AndroidKeyStoreSpi.engineGetCertificateChain#tailHook",
            classDescriptor = "Landroid/security/keystore2/AndroidKeyStoreSpi;",
            methodSignature = "engineGetCertificateChain(Ljava/lang/String;)[Ljava/security/cert/Certificate;",
            checkBannedWords = false
        ) { m ->
            SmaliEngine.removeHookBetweenCertificateChainAputAndReturn(m)
        },

        // Op 3: KeyStore2.getKeyEntry — xoá block từ (noteDiskRead | .end annotation) đến new-instance Lambda
        ScriptOp(
            key = "framework/KeyStore2.getKeyEntry#headHook",
            classDescriptor = "Landroid/security/KeyStore2;",
            methodSignature = "getKeyEntry(Landroid/system/keystore2/KeyDescriptor;)Landroid/system/keystore2/KeyEntryResponse;",
            checkBannedWords = false
        ) { m ->
            // Nếu có dòng noteDiskRead dùng làm anchor, ngược lại dùng .end annotation.
            SmaliEngine.deleteLinesBetween(
                method = m,
                afterContaining = "Landroid/os/StrictMode;->noteDiskRead()V",
                beforeContaining = "new-instance v0, Landroid/security/KeyStore2\$\$ExternalSyntheticLambda",
                fallbackAfter = ".end annotation"
            )
        },

        // Op 4: KeyStore2.getKeyEntry — xoá block giữa check-cast ... KeyEntryResponse; cuối cùng và return-object cuối cùng
        ScriptOp(
            key = "framework/KeyStore2.getKeyEntry#tailHook",
            classDescriptor = "Landroid/security/KeyStore2;",
            methodSignature = "getKeyEntry(Landroid/system/keystore2/KeyDescriptor;)Landroid/system/keystore2/KeyEntryResponse;",
            checkBannedWords = false
        ) { m ->
            val checkCastIdx = m.body.indexOfLast {
                Regex("""^\s*check-cast\s+[vp]\d+,\s*Landroid/system/keystore2/KeyEntryResponse;\s*$""").matches(it)
            }
            if (checkCastIdx < 0) return@ScriptOp
            val retIdx = m.body.indexOfLast {
                Regex("""^\s*return-object\s+[vp]\d+\s*$""").matches(it)
            }
            if (retIdx < 0 || retIdx <= checkCastIdx + 1) return@ScriptOp
            val toRemove = retIdx - checkCastIdx - 1
            repeat(toRemove) { m.body.removeAt(checkCastIdx + 1) }
        },

        // === KaoriosToolbox 1.0.9 Hook Removal ===

        // Op 5: ApplicationPackageManager.hasSystemFeature(String) — thay thế toàn bộ body nếu có hook kaorios
        ScriptOp(
            key = "framework/ApplicationPackageManager.hasSystemFeature_string",
            classDescriptor = "Landroid/app/ApplicationPackageManager;",
            methodSignature = "hasSystemFeature(Ljava/lang/String;)Z",
            checkBannedWords = false
        ) { m ->
            if (!SmaliEngine.bodyContainsKaorios(m)) return@ScriptOp
            SmaliEngine.replaceBodyWithHasSystemFeatureWrapper(m)
        },

        // Op 6: ApplicationPackageManager.hasSystemFeature(String, I) — xóa block KaoriFeatureOverrides nếu có
        ScriptOp(
            key = "framework/ApplicationPackageManager.hasSystemFeature_string_int",
            classDescriptor = "Landroid/app/ApplicationPackageManager;",
            methodSignature = "hasSystemFeature(Ljava/lang/String;I)Z",
            checkBannedWords = false
        ) { m ->
            if (!SmaliEngine.bodyContainsKaorios(m)) return@ScriptOp
            SmaliEngine.removeKaoriFeatureOverridesBlock(m)
        },

        // Op 7: Instrumentation.newApplication(Class, Context) — xóa dòng KaoriProps nếu có (Toolbox 1.0.9).
        ScriptOp(
            key = "framework/Instrumentation.newApplication_ClassContext#KaoriProps",
            classDescriptor = "Landroid/app/Instrumentation;",
            methodSignature = "newApplication(Ljava/lang/Class;Landroid/content/Context;)Landroid/app/Application;",
            checkBannedWords = false
        ) { m ->
            SmaliEngine.removeKaoriPropsInvokeLines(m)
        },

        // Op 8: Instrumentation.newApplication(ClassLoader, String, Context) — xóa dòng KaoriProps nếu có.
        ScriptOp(
            key = "framework/Instrumentation.newApplication_LoaderStringContext#KaoriProps",
            classDescriptor = "Landroid/app/Instrumentation;",
            methodSignature = "newApplication(Ljava/lang/ClassLoader;Ljava/lang/String;Landroid/content/Context;)Landroid/app/Application;",
            checkBannedWords = false
        ) { m ->
            SmaliEngine.removeKaoriPropsInvokeLines(m)
        }
    )

    /** Danh sách tên thư mục (exact, case-sensitive) cần xóa để loại bỏ hook bên trong. */
    private val FORBIDDEN_FOLDER_NAMES = setOf("kaorios", "kousei")

    /**
     * Sau khi commit các chỉnh sửa method: quét tất cả `classesN/` của mọi root,
     * xóa toàn bộ thư mục có tên **khớp chính xác** `kaorios` hoặc `kousei` (`KaoriosA`,
     * `kouseiB` không bị xóa). Đánh dấu dex chứa các folder bị xóa là cần build lại.
     */
    override suspend fun afterCommit(
        ctx: Context,
        workDir: File,
        roots: List<File>,
        progress: (String) -> Unit
    ) {
        progress("Quét thư mục có tên cấm…")
        var totalDeleted = 0
        for (root in roots) {
            val classesDirs = root.listFiles()
                ?.filter { it.isDirectory && it.name.startsWith("classes", ignoreCase = true) }
                ?: continue
            for (cd in classesDirs) {
                val victims = cd.walkTopDown()
                    .filter { it.isDirectory && it.name in FORBIDDEN_FOLDER_NAMES }
                    .toList() // materialize trước khi xóa để tránh ConcurrentModification
                if (victims.isEmpty()) continue
                victims.forEach { v ->
                    val before = totalDeleted
                    FileIo.deleteRecursively(v)
                    totalDeleted++
                    LogSink.d("RemoveHooks", "deleted folder: ${v.absolutePath}")
                    if (before + 1 != totalDeleted) { /* no-op, chỉ để compile không warning */ }
                }
                // Dex chứa folder bị xóa → mark dirty để build lại.
                DexEditedStore.setEditedFlag(root, cd.name, 1)
            }
        }
        progress("Xóa xong $totalDeleted thư mục cấm")
        if (totalDeleted > 0) {
            LogSink.detail("RemoveHooks", "đã xóa $totalDeleted thư mục (tên cấm)")
        }

        // Sau cleanup, có thể có classesN không còn file smali nào (vd dex KaoriosToolbox chỉ
        // chứa folder kaorios/kousei) → drop để build không compile dex rỗng.
        progress("Dọn classesN rỗng…")
        val pruned = ClassesCleanup.pruneEmpty(roots)
        if (pruned > 0) {
            progress("Đã loại $pruned classesN rỗng khỏi workspace")
            LogSink.detail("RemoveHooks", "pruned $pruned empty classesN dir(s)")
        }
    }
}
