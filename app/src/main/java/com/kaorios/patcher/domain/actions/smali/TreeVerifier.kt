package com.kaorios.patcher.domain.actions.smali

import java.io.File

/**
 * Port của `verify-framework-a17-hooks.py`, `verify-services-a17-hooks.py`,
 * `verify-systemserver-a17-hooks.py` và `framework_hook_manifest.py`.
 *
 * Chạy trên cây smali đã giải nén (sau khi patch) để xác nhận mọi caller hook
 * tồn tại đúng một lần, đúng class descriptor, và nội dung khớp cấu trúc mà
 * patcher tương ứng tạo ra.
 */
object TreeVerifier {

    /** 6 caller bắt buộc trong framework — `FRAMEWORK_HOOKS`. */
    val FRAMEWORK_HOOKS: Map<String, String> = linkedMapOf(
        "android/app/ActivityThread.smali" to "patch_activity_thread",
        "android/app/Instrumentation.smali" to "patch_instrumentation",
        "android/app/ApplicationPackageManager.smali" to "patch_app_pkg_manager",
        "android/provider/Settings\$NameValueCache.smali" to "patch_name_value_cache",
        "android/security/keystore2/AndroidKeyStoreSpi.smali" to "patch_keystore_spi",
        "android/security/keystore2/AndroidKeyStoreKeyPairGeneratorSpi.smali" to "patch_keystore_generator"
    )

    /** 8 class Binder mà framework DEX phải chứa. */
    private val ADVANCED_POLICY_CLASSES = listOf(
        "android/security/kaorios/settings/IAdvancedPolicyService.smali",
        "android/security/kaorios/settings/IAdvancedPolicyService\$Stub.smali",
        "android/security/kaorios/settings/IAdvancedPolicyService\$Stub\$Proxy.smali",
        "android/security/kaorios/settings/AdvancedPolicyClient.smali",
        "android/security/kaorios/settings/AdvancedPolicyService.smali",
        "android/security/kaorios/settings/AdvancedPolicySnapshot.smali",
        "android/security/kaorios/settings/ServiceManagerBridge.smali",
        "android/security/kaorios/settings/SettingDecisionParcel.smali"
    )

    private const val HOOK_OWNER = "Landroid/security/kaorios/KaoriosHook;"
    private const val HOOK_SIGNATURE = "initActivityThread(Ljava/lang/Object;)V"

    private val EXACT_HOOK_METHOD = Regex(
        "(?m)^\\.method[^\\r\\n]*[ \\t]" + Regex.escape(HOOK_SIGNATURE) + "[ \\t]*(?:\\r?\\n|$)"
    )
    private val HOOK_CLASS_LINE = Regex(
        "(?m)^\\.class\\s+[^\\r\\n]*\\bpublic\\b[^\\r\\n]* " + Regex.escape(HOOK_OWNER) + "[ \\t]*$"
    )
    private val CLASS_DECLARATION = Regex(
        "(?m)^\\.class[ \\t]+(?:[^\\r\\n]*?[ \\t]+)?(L[^\\s;]+;)[ \\t]*$"
    )

    /**
     * @return caller đã verify đầu tiên, callee nếu được yêu cầu, và danh sách
     *         AdvancedPolicy class đã verify.
     */
    fun verifyFramework(
        smaliRoot: File,
        requireCallee: Boolean = true,
        requireAdvancedPolicy: Boolean = true
    ): Triple<File, File?, List<File>> {
        require(smaliRoot.isDirectory) { "$smaliRoot is not a directory" }

        val callers = FRAMEWORK_HOOKS.keys.map { relative ->
            val path = exactlyOne(smaliRoot, relative)
            val expected = "L" + relative.removeSuffix(".smali") + ";"
            val declarations = CLASS_DECLARATION.findAll(path.readText(Charsets.UTF_8))
                .map { it.groupValues[1] }
                .toList()
            require(declarations == listOf(expected)) { "$path: expected class descriptor $expected" }
            TargetPatcher.verify(path.name, path.readText(Charsets.UTF_8))
            path
        }

        val callee = if (requireCallee) verifyKaoriosHook(smaliRoot) else null
        val advanced = if (requireAdvancedPolicy) verifyAdvancedPolicyClasses(smaliRoot) else emptyList()
        return Triple(callers.first(), callee, advanced)
    }

    fun verifyServices(smaliRoot: File): File {
        require(smaliRoot.isDirectory) { "$smaliRoot is not a directory" }
        val computerEngine = exactlyOne(smaliRoot, "com/android/server/pm/ComputerEngine.smali")
        ComputerEnginePatch.verify(computerEngine.readText(Charsets.UTF_8))
        return computerEngine
    }

    /**
     * Đúng nhánh framework của `run_directory_verifiers`: chỉ tìm ActivityThread và chạy
     * verifier của patcher tương ứng (KHÔNG phải `verify_root` đầy đủ).
     */
    fun verifyActivityThread(smaliRoot: File): File {
        require(smaliRoot.isDirectory) { "$smaliRoot is not a directory" }
        val activityThread = exactlyOne(smaliRoot, "android/app/ActivityThread.smali")
        ActivityThreadPatch.verify(activityThread.readText(Charsets.UTF_8))
        return activityThread
    }

    fun verifySystemServer(smaliRoot: File): File {
        require(smaliRoot.isDirectory) { "$smaliRoot is not a directory" }
        val systemServer = exactlyOne(smaliRoot, "com/android/server/SystemServer.smali")
        SystemServerPatch.verify(systemServer.readText(Charsets.UTF_8))
        return systemServer
    }

    /** `validate_kaorios_hook_smali` + kiểm tra file tồn tại đúng một lần. */
    fun verifyKaoriosHook(smaliRoot: File): File {
        val hookFile = exactlyOne(smaliRoot, "android/security/kaorios/KaoriosHook.smali")
        val text = hookFile.readText(Charsets.UTF_8)

        require(HOOK_CLASS_LINE.containsMatchIn(text)) {
            "smali does not define $HOOK_OWNER"
        }
        val matches = EXACT_HOOK_METHOD.findAll(text).toList()
        require(matches.size == 1) {
            "expected exactly one exact Object overload $HOOK_SIGNATURE in KaoriosHook; found ${matches.size}"
        }
        val flags = matches[0].value.substringBefore("initActivityThread")
            .split(Regex("\\s+")).toSet()
        require("public" in flags && "static" in flags &&
            "abstract" !in flags && "native" !in flags
        ) { "KaoriosHook Object overload must be public static and implemented" }
        return hookFile
    }

    private fun verifyAdvancedPolicyClasses(smaliRoot: File): List<File> {
        val verified = mutableListOf<File>()
        val missing = mutableListOf<String>()
        for (relative in ADVANCED_POLICY_CLASSES) {
            val matches = matches(smaliRoot, relative)
            when (matches.size) {
                0 -> missing += relative
                1 -> verified += matches[0]
                else -> error("duplicate AdvancedPolicy class: $relative")
            }
        }
        require(missing.isEmpty()) {
            "Kaorios framework DEX missing required AdvancedPolicy Binder classes: $missing"
        }
        return verified
    }

    private fun exactlyOne(smaliRoot: File, relative: String): File {
        val matches = matches(smaliRoot, relative)
        require(matches.size == 1) {
            "expected exactly one $relative below $smaliRoot; found ${matches.size}"
        }
        return matches[0]
    }

    /** `rglob` của Python: khớp theo đuôi đường dẫn, sắp xếp ổn định. */
    private fun matches(smaliRoot: File, relative: String): List<File> =
        smaliRoot.walkTopDown()
            .filter { it.isFile }
            .filter { it.invariantSeparatorsPath.endsWith("/$relative") || it.name == relative }
            .sortedBy { it.invariantSeparatorsPath }
            .toList()
}
