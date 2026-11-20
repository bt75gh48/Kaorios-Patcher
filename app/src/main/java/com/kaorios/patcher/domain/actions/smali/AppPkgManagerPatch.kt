package com.kaorios.patcher.domain.actions.smali

/**
 * Port của `patch_app_pkg_manager` từ `kaorios_patcher.py`.
 *
 * `ApplicationPackageManager.hasSystemFeature(String, I)Z` được chèn hook
 * `KaoriosHook.hasSystemFeature(String, I)Boolean` ngay đầu method. Khi hook trả
 * null (không có quyết định cho package), control flow rơi xuống logic gốc.
 */
object AppPkgManagerPatch {

    private const val HOOK =
        "Landroid/security/kaorios/KaoriosHook;->hasSystemFeature(Ljava/lang/String;I)Ljava/lang/Boolean;"
    private const val LABEL = ":cond_kaorios_feature_stock"

    private val METHOD_RE = Regex(
        "(\\.method[^\\n]*?hasSystemFeature\\(Ljava/lang/String;I\\)Z.*?\\.end method)",
        RegexOption.DOT_MATCHES_ALL
    )
    private val REGISTER_DIRECTIVE = Regex("\\.(registers|locals)\\s+(\\d+)([^\\n]*)")

    fun patch(content: String, update: Boolean = false): Pair<String, Boolean> {
        val match = METHOD_RE.find(content) ?: error("hasSystemFeature(Ljava/lang/String;I)Z method not found in ApplicationPackageManager")
        var methodBody = match.groupValues[1]

        if ("KaoriosHook;->hasSystemFeature" in methodBody) {
            try {
                verify(content)
                return content to false
            } catch (e: IllegalArgumentException) {
                require(update) { e.message ?: "ApplicationPackageManager: invalid existing hook" }
            }
            val hookRe = Regex(
                "(?m)^[ \\t]*invoke-static(?:/range)?[^\\n]*KaoriosHook;->hasSystemFeature[^\\n]*\\n" +
                    "[ \\t]*move-result-object\\s+[vp]\\d+\\n" +
                    "(?:\\s*\\n)*" +
                    "[ \\t]*if-eqz[^\\n]*\\n" +
                    "[ \\t]*invoke-virtual(?:/range)?[^\\n]*Boolean;->booleanValue\\(\\)Z\\n" +
                    "[ \\t]*move-result\\s+[vp]\\d+\\n" +
                    "[ \\t]*return\\s+[vp]\\d+\\n" +
                    "(?:\\s*\\n)*" +
                    "[ \\t]*$LABEL[^\\n]*\\n?"
            )
            val stripped = KeystorePatch.removeLegacyRegisterHook(methodBody, hookRe, 3)
            val strippedContent = content.substring(0, match.range.first) + stripped + content.substring(match.range.last + 1)
            return patch(strippedContent, update = false)
        }

        val regMatch = REGISTER_DIRECTIVE.find(methodBody)
            ?: error(".registers or .locals directive not found in hasSystemFeature")
        val directive = regMatch.groupValues[1]
        val oldCount = regMatch.groupValues[2].toInt()
        val newCount = oldCount + 1

        val scratch: String
        val scratchNum: Int
        val p1Num: Int
        val p2Num: Int

        if (directive == "locals") {
            scratchNum = oldCount
            scratch = "v$scratchNum"
            methodBody = SmaliOps.canonicalizeParamAliases(methodBody, oldCount + 3, 3)
            p1Num = oldCount + 2
            p2Num = oldCount + 3
        } else {
            val paramCount = 3 // p0 = this, p1 = String, p2 = int
            methodBody = SmaliOps.canonicalizeParamAliases(methodBody, oldCount, paramCount)
            scratchNum = newCount - paramCount - 1
            scratch = "v$scratchNum"
            p1Num = newCount - paramCount + 1
            p2Num = newCount - paramCount + 2
        }

        val newDirective = ".$directive $newCount${regMatch.groupValues[3]}"
        val label = SmaliOps.uniqueLabel(LABEL, methodBody)

        val invokeStatic = if (p1Num > 15 || p2Num > 15) {
            "invoke-static/range {p1 .. p2}, $HOOK"
        } else {
            "invoke-static {p1, p2}, $HOOK"
        }
        val invokeVirtual = if (scratchNum > 15) {
            "invoke-virtual/range {$scratch .. $scratch}, Ljava/lang/Boolean;->booleanValue()Z"
        } else {
            "invoke-virtual {$scratch}, Ljava/lang/Boolean;->booleanValue()Z"
        }

        val inject = "\n    $invokeStatic\n    move-result-object $scratch\n\n" +
            "    if-eqz $scratch, $label\n    $invokeVirtual\n    move-result $scratch\n" +
            "    return $scratch\n\n    $label"

        val newMethod = methodBody.substring(0, regMatch.range.first) +
            newDirective + inject +
            methodBody.substring(regMatch.range.last + 1)
        return content.substring(0, match.range.first) + newMethod + content.substring(match.range.last + 1) to true
    }

    fun verify(content: String) {
        val match = METHOD_RE.find(content)
            ?: error("ApplicationPackageManager: hasSystemFeature(Ljava/lang/String;I)Z method not found")
        val body = match.groupValues[1]

        val directive = Regex("\\.(registers|locals)\\s+(\\d+)").find(body)
            ?: error("hasSystemFeature register directive not found")
        val registers = directive.groupValues[2].toInt() + (if (directive.groupValues[1] == "locals") 3 else 0)
        SmaliOps.verifyRegisterEncoding(body, registers, 3)

        val sequence = Regex(
            "invoke-static(?:/range)?\\s*\\{p1(?:,\\s*p2|\\s*\\.\\.\\s*p2)\\},\\s*" +
                Regex.escape(HOOK) + "\\s+" +
                "move-result-object\\s+(?<scratch>v\\d+)\\s+" +
                "if-eqz\\s+\\k<scratch>,\\s*(?<label>:[\\w$]+)\\s+" +
                "invoke-virtual(?:/range)?\\s*\\{\\k<scratch>(?:\\s*\\.\\.\\s*\\k<scratch>)?\\},\\s*" +
                "Ljava/lang/Boolean;->booleanValue\\(\\)Z\\s+" +
                "move-result\\s+\\k<scratch>\\s+" +
                "return\\s+\\k<scratch>\\s+" +
                "(?:\\.line\\s+\\d+\\s+)*" +
                "\\k<label>\\b"
        ).find(body)
        require(sequence != null && body.split("KaoriosHook;->hasSystemFeature").size - 1 == 1) {
            "ApplicationPackageManager: invalid hasSystemFeature hook control flow"
        }
        val label = sequence!!.groups["label"]!!.value
        require(Regex("(?m)^\\s*${Regex.escape(label)}\\s*$").findAll(body).count() == 1) {
            "ApplicationPackageManager: stock branch target is not unique"
        }
        val prefix = Regex("(?m)^\\s*\\.(?:method|registers|locals|param|line)\\b[^\\n]*$")
            .replace(body.substring(0, sequence.range.first), "")
        require(prefix.isBlank()) { "ApplicationPackageManager: hook is after stock logic" }
    }
}
