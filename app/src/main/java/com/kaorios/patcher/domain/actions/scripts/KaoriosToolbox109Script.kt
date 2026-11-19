package com.kaorios.patcher.domain.actions.scripts

import android.content.Context
import com.kaorios.patcher.data.log.LogSink
import com.kaorios.patcher.domain.actions.ActionScript
import com.kaorios.patcher.domain.actions.ActionState
import com.kaorios.patcher.domain.actions.DexDownloader
import com.kaorios.patcher.domain.actions.ScriptOp
import com.kaorios.patcher.domain.actions.SmaliEngine
import com.kaorios.patcher.domain.actions.Toolbox109Config
import com.kaorios.patcher.domain.usecase.ImportDexUseCase
import java.io.File

/**
 * KaoriosToolbox 1.0.9 — chèn hook gọi sang `Lcom/android/internal/util/kaorios/...` thay vì
 * `Landroid/security/kaorios/KaoriosHook;` như bản 2036.
 *
 * So với 2036:
 * - [hasSystemFeature] dùng `KaoriFeatureOverrides.getOverride(ctx, feature, pkg)` với try/catch.
 * - [newApplication] dùng `KaoriPropsUtils.KaoriProps(ctx)`.
 * - [engineGetCertificateChain] chèn thêm `KaoriPropsUtils.KaoriGetCertificateChain()` đầu method
 *   và thay hook chain bằng `KaoriKeyboxHooks.KaoriGetCertificateChain([Cert;)`.
 * - BỎ op `AndroidKeyStoreKeyPairGeneratorSpi.generateKeyPair` và `SystemServer.run` (bản 109 không cần).
 *
 * Cùng repo với 2036, khác tag (default "V1.0.9" — user có thể đổi trong dialog).
 */
object KaoriosToolbox109Script : ActionScript {
    override val id = "kaoriostoolbox_109"
    override val displayName = "KaoriosToolbox 1.0.9"
    override val workingDirName = "kaoriostoolbox_109"

    // Cùng repo với 2036 (chỉ tag khác). Dùng chung REPO_* với 2036 để đơn giản.
    const val REPO_OWNER = KaoriosToolbox2036Script.REPO_OWNER
    const val REPO_NAME = KaoriosToolbox2036Script.REPO_NAME

    override val operations: List<ScriptOp> = listOf(
        // Op 1: ApplicationPackageManager.hasSystemFeature(String, I)Z — chèn hook feature override sau .registers.
        //   Block gồm try/catch gọi KaoriFeatureOverrides.getOverride(ctx, feature, pkg).
        //   KHÔNG check banned vì smali gốc AOSP không có keyword cấm.
        ScriptOp(
            key = "framework/ApplicationPackageManager.hasSystemFeature",
            classDescriptor = "Landroid/app/ApplicationPackageManager;",
            methodSignature = "hasSystemFeature(Ljava/lang/String;I)Z",
            checkBannedWords = false
        ) { m ->
            val block = """
                invoke-static {}, Landroid/app/ActivityThread;->currentPackageName()Ljava/lang/String;

                move-result-object v0

                :try_start_kaori_override
                iget-object v1, p0, Landroid/app/ApplicationPackageManager;->mContext:Landroid/app/ContextImpl;

                invoke-static {v1, p1, v0}, Lcom/android/internal/util/kaorios/KaoriFeatureOverrides;->getOverride(Landroid/content/Context;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Boolean;

                move-result-object v0
                :try_end_kaori_override
                .catchall {:try_start_kaori_override .. :try_end_kaori_override} :catchall_kaori_override

                goto :goto_kaori_override

                :catchall_kaori_override
                const/4 v0, 0x0

                :goto_kaori_override
                if-eqz v0, :cond_kaori_override

                invoke-virtual {v0}, Ljava/lang/Boolean;->booleanValue()Z

                move-result p0

                return p0

                :cond_kaori_override
            """.trimIndent()
            SmaliEngine.insertAfterRegisters(m, block)
        },

        // Op 2: Instrumentation.newApplication(Class, Context) — chèn KaoriProps(p1) trước return-object cuối. KHÔNG check banned.
        ScriptOp(
            key = "framework/Instrumentation.newApplication_ClassContext",
            classDescriptor = "Landroid/app/Instrumentation;",
            methodSignature = "newApplication(Ljava/lang/Class;Landroid/content/Context;)Landroid/app/Application;",
            checkBannedWords = false
        ) { m ->
            val hook = "invoke-static {p1}, Lcom/android/internal/util/kaorios/KaoriPropsUtils;->KaoriProps(Landroid/content/Context;)V"
            SmaliEngine.insertBeforeLastReturnObject(m, hook)
        },

        // Op 3: Instrumentation.newApplication(ClassLoader, String, Context) — chèn KaoriProps(p3). KHÔNG check banned.
        ScriptOp(
            key = "framework/Instrumentation.newApplication_LoaderStringContext",
            classDescriptor = "Landroid/app/Instrumentation;",
            methodSignature = "newApplication(Ljava/lang/ClassLoader;Ljava/lang/String;Landroid/content/Context;)Landroid/app/Application;",
            checkBannedWords = false
        ) { m ->
            val hook = "invoke-static {p3}, Lcom/android/internal/util/kaorios/KaoriPropsUtils;->KaoriProps(Landroid/content/Context;)V"
            SmaliEngine.insertBeforeLastReturnObject(m, hook)
        },

        // Op 4: AndroidKeyStoreSpi.engineGetCertificateChain — hai insert trong cùng 1 method:
        //   (a) Sau .registers: gọi KaoriPropsUtils.KaoriGetCertificateChain() (no-arg hint).
        //   (b) Sau sequence const/4 + aput-object + return-object: thay hook chain bằng KaoriKeyboxHooks.
        //   CHECK banned — nếu smali đã có keyword cấm → skip (tránh patch lần 2).
        ScriptOp(
            key = "framework/AndroidKeyStoreSpi.engineGetCertificateChain",
            classDescriptor = "Landroid/security/keystore2/AndroidKeyStoreSpi;",
            methodSignature = "engineGetCertificateChain(Ljava/lang/String;)[Ljava/security/cert/Certificate;",
            checkBannedWords = true
        ) { m ->
            // (a) Đầu method — hint KaoriProps chuẩn bị certificate chain.
            val headHook = "invoke-static {}, Lcom/android/internal/util/kaorios/KaoriPropsUtils;->KaoriGetCertificateChain()V"
            SmaliEngine.insertAfterRegisters(m, headHook)

            // (b) Thay kết quả chain bằng giá trị fake từ KaoriKeyboxHooks.
            SmaliEngine.insertHookAfterAputObjectSequence(m) { xB, xD ->
                """
                invoke-static {$xB}, Lcom/android/internal/util/kaorios/KaoriKeyboxHooks;->KaoriGetCertificateChain([Ljava/security/cert/Certificate;)[Ljava/security/cert/Certificate;

                move-result-object $xD
                """.trimIndent()
            }
        }
        // Không có op cho AndroidKeyStoreKeyPairGeneratorSpi và SystemServer — bản 109 không cần.
    )

    /**
     * Sau khi commit OK: tải dex KaoriosToolbox 1.0.9 từ GitHub release và import vào
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
        val tag = Toolbox109Config.currentTag
        val cacheKey = "kaoriostoolbox_${tag.replace('/', '_')}"
        progress("Tải dex Toolbox $tag từ GitHub…")
        LogSink.detail("Actions", "Toolbox dex · $REPO_OWNER/$REPO_NAME @$tag (109)")

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

        ActionState.setScriptTag(workDir, ActionState.SCRIPT_ID_TOOLBOX_109, tag)
        progress("Import Toolbox dex xong → $imported (tag $tag đã ghi nhận)")
    }
}
