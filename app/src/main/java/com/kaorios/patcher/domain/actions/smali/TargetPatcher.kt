package com.kaorios.patcher.domain.actions.smali

/** Trạng thái patch của một file mục tiêu — khớp `PatchStatus` bên Python. */
enum class TargetStatus {
    PATCHED,
    ALREADY_PATCHED,
    UNSUPPORTED_LAYOUT,
    FAILED,
    NOT_TARGET
}

data class TargetResult(
    val status: TargetStatus,
    val content: String,
    val error: String? = null
)

/**
 * Port của `apply_target_patch` + `verify_target_content` từ `kaorios_patcher.py`.
 *
 * Lớp này là cửa ngõ duy nhất để patch một file smali của framework: nó kiểm tra
 * class descriptor, từ chối method trùng tên, chạy đúng patcher theo tên file, rồi
 * chạy verifier tương ứng trên nội dung đã patch.
 */
object TargetPatcher {

    /** Tên file → class descriptor bắt buộc. */
    val TARGETS: Map<String, String> = linkedMapOf(
        "ActivityThread.smali" to "Landroid/app/ActivityThread;",
        "ComputerEngine.smali" to "Lcom/android/server/pm/ComputerEngine;",
        "SystemServer.smali" to "Lcom/android/server/SystemServer;",
        "AndroidKeyStoreKeyPairGeneratorSpi.smali" to
            "Landroid/security/keystore2/AndroidKeyStoreKeyPairGeneratorSpi;",
        "AndroidKeyStoreSpi.smali" to "Landroid/security/keystore2/AndroidKeyStoreSpi;",
        "Instrumentation.smali" to "Landroid/app/Instrumentation;",
        "ApplicationPackageManager.smali" to "Landroid/app/ApplicationPackageManager;",
        "Settings\$NameValueCache.smali" to "Landroid/provider/Settings\$NameValueCache;"
    )

    /**
     * Patch set gốc (mode 1): 8 hook. Build spoof (Build + Build$VERSION) do
     * `SmaliEngine.patchBuildFields` xử lý riêng vì nó thao tác trên hằng số, không
     * phải chèn hook.
     */
    val HOOK_TARGETS: Set<String> = TARGETS.keys

    private val CLASS_DECLARATION = Regex(
        "(?m)^\\.class[ \\t]+(?:[^\\r\\n]*?[ \\t]+)?(L[^\\s;]+;)[ \\t]*$"
    )
    private val METHOD_HEADERS = Regex("(?m)^\\.method[^\\n]*?\\s+([^\\s(]+\\([^\\n]*)$")

    fun isTarget(filename: String): Boolean = filename in TARGETS

    fun apply(filename: String, content: String, update: Boolean = false): TargetResult {
        val owner = TARGETS[filename]
            ?: return TargetResult(TargetStatus.NOT_TARGET, content, null)

        val declarations = CLASS_DECLARATION.findAll(content).map { it.groupValues[1] }.toList()
        if (declarations != listOf(owner)) {
            return TargetResult(
                TargetStatus.UNSUPPORTED_LAYOUT,
                content,
                "$filename: expected class descriptor $owner"
            )
        }

        return try {
            val headers = METHOD_HEADERS.findAll(content).map { it.groupValues[1] }.toList()
            require(headers.size == headers.toSet().size) {
                "$filename: ambiguous duplicate method declaration"
            }

            val (patched, changed) = when (filename) {
                "ActivityThread.smali" -> ActivityThreadPatch.patch(content, update)
                "ComputerEngine.smali" -> ComputerEnginePatch.patch(content)
                "SystemServer.smali" -> SystemServerPatch.patch(content)
                "AndroidKeyStoreKeyPairGeneratorSpi.smali" -> KeystorePatch.patchGenerator(content, update)
                "AndroidKeyStoreSpi.smali" -> KeystorePatch.patchSpi(content, update)
                "Instrumentation.smali" -> InstrumentationPatch.patch(content, update)
                "ApplicationPackageManager.smali" -> AppPkgManagerPatch.patch(content, update)
                "Settings\$NameValueCache.smali" -> NameValueCachePatch.patch(content, update)
                else -> error("unreachable target $filename")
            }

            verify(filename, patched)
            if (changed) TargetResult(TargetStatus.PATCHED, patched, null)
            else TargetResult(TargetStatus.ALREADY_PATCHED, content, null)
        } catch (e: IllegalArgumentException) {
            classify(filename, content, e)
        } catch (e: IllegalStateException) {
            classify(filename, content, e)
        }
    }

    fun verify(filename: String, content: String) {
        when (filename) {
            "ActivityThread.smali" -> ActivityThreadPatch.verify(content)
            "ComputerEngine.smali" -> ComputerEnginePatch.verify(content)
            "SystemServer.smali" -> SystemServerPatch.verify(content)
            "AndroidKeyStoreKeyPairGeneratorSpi.smali" -> KeystorePatch.verifyGenerator(content)
            "AndroidKeyStoreSpi.smali" -> KeystorePatch.verifySpi(content)
            "Instrumentation.smali" -> InstrumentationPatch.verify(content)
            "ApplicationPackageManager.smali" -> AppPkgManagerPatch.verify(content)
            "Settings\$NameValueCache.smali" -> NameValueCachePatch.verify(content)
            else -> error("$filename is not a hook target")
        }
    }

    private fun classify(filename: String, content: String, e: Exception): TargetResult {
        val message = e.message ?: e.javaClass.simpleName
        val lower = message.lowercase()
        val layoutFailure = listOf(
            "anchor", "not found", "ambiguous", "unterminated", "unsupported",
            "missing", "expected exactly one", "found 0"
        ).any { it in lower }
        val status = if (layoutFailure) TargetStatus.UNSUPPORTED_LAYOUT else TargetStatus.FAILED
        return TargetResult(status, content, message)
    }
}
