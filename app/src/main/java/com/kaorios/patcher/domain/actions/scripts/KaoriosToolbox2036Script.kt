package com.kaorios.patcher.domain.actions.scripts

import android.content.Context
import com.kaorios.patcher.data.log.LogSink
import com.kaorios.patcher.domain.actions.ActionScript
import com.kaorios.patcher.domain.actions.ActionState
import com.kaorios.patcher.domain.actions.DexDownloader
import com.kaorios.patcher.domain.actions.ScriptOp
import com.kaorios.patcher.domain.actions.SmaliEngine
import com.kaorios.patcher.domain.actions.Toolbox2036Config
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
        // Op 1: ApplicationPackageManager.hasSystemFeature — chèn hook sau .registers, KHÔNG check banned.
        ScriptOp(
            key = "framework/ApplicationPackageManager.hasSystemFeature",
            classDescriptor = "Landroid/app/ApplicationPackageManager;",
            methodSignature = "hasSystemFeature(Ljava/lang/String;I)Z",
            checkBannedWords = false
        ) { m ->
            val block = """
                invoke-static {p1, p2}, Landroid/security/kaorios/KaoriosHook;->hasSystemFeature(Ljava/lang/String;I)Ljava/lang/Boolean;

                move-result-object v0

                if-eqz v0, :cond_kaorios

                invoke-virtual {v0}, Ljava/lang/Boolean;->booleanValue()Z

                move-result v0

                return v0

                :cond_kaorios
            """.trimIndent()
            SmaliEngine.insertAfterRegisters(m, block)
        },

        // Op 2: Instrumentation.newApplication(Class, Context) — chèn initContext(p1) trước return-object cuối. KHÔNG check banned.
        ScriptOp(
            key = "framework/Instrumentation.newApplication_ClassContext",
            classDescriptor = "Landroid/app/Instrumentation;",
            methodSignature = "newApplication(Ljava/lang/Class;Landroid/content/Context;)Landroid/app/Application;",
            checkBannedWords = false
        ) { m ->
            val hook = "invoke-static {p1}, Landroid/security/kaorios/KaoriosHook;->initContext(Landroid/content/Context;)V"
            SmaliEngine.insertBeforeLastReturnObject(m, hook)
        },

        // Op 3: Instrumentation.newApplication(ClassLoader, String, Context) — chèn initContext(p3). KHÔNG check banned.
        ScriptOp(
            key = "framework/Instrumentation.newApplication_LoaderStringContext",
            classDescriptor = "Landroid/app/Instrumentation;",
            methodSignature = "newApplication(Ljava/lang/ClassLoader;Ljava/lang/String;Landroid/content/Context;)Landroid/app/Application;",
            checkBannedWords = false
        ) { m ->
            val hook = "invoke-static {p3}, Landroid/security/kaorios/KaoriosHook;->initContext(Landroid/content/Context;)V"
            SmaliEngine.insertBeforeLastReturnObject(m, hook)
        },

        // Op 4: AndroidKeyStoreKeyPairGeneratorSpi.generateKeyPair — tăng .registers +1, chèn hook dùng reg mới. KHÔNG check banned.
        ScriptOp(
            key = "framework/AndroidKeyStoreKeyPairGeneratorSpi.generateKeyPair",
            classDescriptor = "Landroid/security/keystore2/AndroidKeyStoreKeyPairGeneratorSpi;",
            methodSignature = "generateKeyPair()Ljava/security/KeyPair;",
            checkBannedWords = false
        ) { m ->
            val newReg = SmaliEngine.incrementRegistersAndReturnFree(m)
            val block = """
                invoke-static {p0}, Landroid/security/kaorios/KaoriosHook;->initGenerateSoftwareKeyPair(Ljava/lang/Object;)Ljava/security/KeyPair;

                move-result-object $newReg

                if-eqz $newReg, :cond_kaorios

                return-object $newReg

                :cond_kaorios
            """.trimIndent()
            SmaliEngine.insertAfterRegisters(m, block)
        },

        // Op 5: AndroidKeyStoreSpi.engineGetCertificateChain — chèn CertificateChainIfNeeded sau aput-object.
        // Không check banned: ROM có thể còn invoke tới `.../kaorios/...` (KaoriKeyboxHooks, v.v.) trước khi gỡ sạch.
        ScriptOp(
            key = "framework/AndroidKeyStoreSpi.engineGetCertificateChain",
            classDescriptor = "Landroid/security/keystore2/AndroidKeyStoreSpi;",
            methodSignature = "engineGetCertificateChain(Ljava/lang/String;)[Ljava/security/cert/Certificate;",
            checkBannedWords = false
        ) { m ->
            SmaliEngine.insertHookAfterAputObjectSequence(m) { xB, xD ->
                """
                invoke-static {$xB}, Landroid/security/kaorios/KaoriosHook;->CertificateChainIfNeeded([Ljava/security/cert/Certificate;)[Ljava/security/cert/Certificate;

                move-result-object $xD
                """.trimIndent()
            }
        },

        // Op 6: SystemServer — chèn initSystemServer() trước invoke startOtherServices (entry có thể là run hoặc main).
        ScriptOp(
            key = "services/SystemServer.initSystemServer",
            classDescriptor = "Lcom/android/server/SystemServer;",
            methodSignature = "run()V",
            methodAnchorLineContains = "Lcom/android/server/SystemServer;->startOtherServices(Lcom/android/server/utils/TimingsTraceAndSlog;)V",
            checkBannedWords = false
        ) { m ->
            val hook = "invoke-static {}, Landroid/security/kaorios/KaoriosHook;->initSystemServer()V"
            SmaliEngine.insertBeforeLineContaining(
                m,
                "Lcom/android/server/SystemServer;->startOtherServices(Lcom/android/server/utils/TimingsTraceAndSlog;)V",
                hook
            )
        }
    )

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

        // Lưu tag vào ActionState — Build module sẽ dùng để chọn đúng module zip.
        ActionState.setScriptTag(workDir, ActionState.SCRIPT_ID_TOOLBOX_2036, tag)
        progress("Import Toolbox dex xong → $imported (tag $tag đã ghi nhận)")
    }
}
