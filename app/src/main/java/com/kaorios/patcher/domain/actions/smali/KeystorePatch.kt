package com.kaorios.patcher.domain.actions.smali

/**
 * Port của `patch_keystore_generator`, `_patch_certificate_chain`, `patch_keystore_spi`
 * từ `kaorios_patcher.py`.
 */
object KeystorePatch {

    private const val GEN_HOOK =
        "Landroid/security/kaorios/KaoriosHook;->initGenerateSoftwareKeyPair(Ljava/lang/Object;)Ljava/security/KeyPair;"

    private const val CHAIN_ONE_ARG =
        "Landroid/security/kaorios/KaoriosHook;->CertificateChainIfNeeded" +
            "([Ljava/security/cert/Certificate;)[Ljava/security/cert/Certificate;"
    private const val CHAIN_THREE_ARG =
        "Landroid/security/kaorios/KaoriosHook;->CertificateChainIfNeeded" +
            "(Ljava/lang/Object;Ljava/lang/String;[Ljava/security/cert/Certificate;)" +
            "[Ljava/security/cert/Certificate;"

    private val REGISTER_DIRECTIVE = Regex("\\.(registers|locals)\\s+(\\d+)")

    // ==================================================================
    // AndroidKeyStoreKeyPairGeneratorSpi
    // ==================================================================

    fun patchGenerator(content: String, update: Boolean = false): Pair<String, Boolean> {
        val start = content.indexOf("generateKeyPair()Ljava/security/KeyPair;").also {
            require(it != -1) { "generateKeyPair() anchor method not found in KeyPairGeneratorSpi" }
        }
        val end = content.indexOf(".end method", start).also {
            require(it != -1) { "unterminated generateKeyPair method in KeyPairGeneratorSpi" }
        }

        val methodBody = content.substring(start, end)

        if ("KaoriosHook;->initGenerateSoftwareKeyPair" in methodBody) {
            try {
                verifyGenerator(content)
                return content to false
            } catch (e: IllegalArgumentException) {
                if (!update) throw e
            }
            // Gỡ block hook cũ (chỉ khi nó sở hữu local cuối) rồi patch lại từ đầu.
            val hookRe = Regex(
                "(?m)^[ \\t]*invoke-static(?:/range)?[^\\n]*KaoriosHook;->initGenerateSoftwareKeyPair[^\\n]*\\n" +
                    "[ \\t]*move-result-object\\s+[vp]\\d+\\n" +
                    "(?:\\s*\\n)*" +
                    "[ \\t]*if-eqz[^\\n]*\\n" +
                    "[ \\t]*return-object\\s+[vp]\\d+\\n" +
                    "(?:\\s*\\n)*" +
                    "[ \\t]*:cond_kaorios_gen_stock[^\\n]*\\n?"
            )
            val strippedBody = removeLegacyRegisterHook(methodBody, hookRe, 1)
            val strippedContent = content.substring(0, start) + strippedBody + content.substring(end)
            return patchGenerator(strippedContent, update = false)
        }

        val match = REGISTER_DIRECTIVE.find(methodBody)
            ?: error(".registers or .locals directive not found in generateKeyPair")

        val directive = match.groupValues[1]
        val oldReg = match.groupValues[2].toInt()
        val newReg = oldReg + 1

        var body = methodBody
        val vTarget: String
        val p0Num: Int

        if (directive == "registers") {
            // generateKeyPair() là virtual method: p0 = this.
            body = SmaliOps.canonicalizeParamAliases(body, oldReg, 1)
            vTarget = "v${newReg - 2}"
            p0Num = newReg - 1
        } else {
            body = SmaliOps.canonicalizeParamAliases(body, oldReg + 1, 1)
            vTarget = "v$oldReg"
            p0Num = oldReg + 1
        }

        val label = SmaliOps.uniqueLabel(":cond_kaorios_gen_stock", body)
        val invokeStr = if (p0Num > 15) {
            "invoke-static/range {p0 .. p0}, $GEN_HOOK"
        } else {
            "invoke-static {p0}, $GEN_HOOK"
        }

        // Directive trong `body` có thể đã lệch offset so với `match` sau khi canonicalize;
        // tìm lại để chèn đúng chỗ.
        val newDirective = REGISTER_DIRECTIVE.find(body)!!
        val inject = "\n    $invokeStr\n    move-result-object $vTarget\n\n" +
            "    if-eqz $vTarget, $label\n    return-object $vTarget\n\n    $label\n"
        val entry = generatorEntryOffset(body, newDirective.range.last + 1)
        val newBody = body.substring(0, newDirective.range.first) +
            ".$directive $newReg" +
            body.substring(newDirective.range.last + 1, entry) +
            inject +
            body.substring(entry)
        return content.substring(0, start) + newBody + content.substring(end) to true
    }

    fun verifyGenerator(content: String) {
        val body = extractMethodBody(content, "generateKeyPair()Ljava/security/KeyPair;", "AndroidKeyStoreKeyPairGeneratorSpi")
        val count = Regex(Regex.escape("KaoriosHook;->initGenerateSoftwareKeyPair")).findAll(body).count()
        require(count == 1) {
            "AndroidKeyStoreKeyPairGeneratorSpi: expected exactly 1 initGenerateSoftwareKeyPair hook " +
                "in generateKeyPair, found $count"
        }
        val directive = REGISTER_DIRECTIVE.find(body)
            ?: error("generateKeyPair register directive not found")
        val registers = directive.groupValues[2].toInt() + (if (directive.groupValues[1] == "locals") 1 else 0)
        SmaliOps.verifyRegisterEncoding(body, registers, 1)
        val entry = generatorEntryOffset(body, directive.range.last + 1)

        val sequence = Regex(
            "\\s*invoke-static(?<range>/range)?\\s*\\{p0(?:\\s*\\.\\.\\s*p0)?\\},\\s*" +
                Regex.escape(GEN_HOOK) + "\\s+" +
                "move-result-object\\s+(?<scratch>v\\d+)\\s+" +
                "if-eqz\\s+\\k<scratch>,\\s*(?<label>:[\\w$]+)\\s+" +
                "return-object\\s+\\k<scratch>\\s*" +
                "(?:\\.(?:line|local|end local|restart local)[^\\n]*\\n|\\s)*" +
                "\\k<label>\\s*"
        ).find(body.substring(entry))
            ?: error("generateKeyPair hook must be at entry with a dedicated local and stock fallback")

        val scratch = sequence.groups["scratch"]!!.value
        require(scratch.drop(1).toInt() == registers - 2) {
            "generateKeyPair hook must be at entry with a dedicated local and stock fallback"
        }
        val label = sequence.groups["label"]!!.value
        require(Regex("(?m)^\\s*${Regex.escape(label)}\\s*$").findAll(body).count() == 1) {
            "generateKeyPair stock fallback label is not unique"
        }
        val stockLines = body.substring(entry + sequence.range.last + 1).split("\n")
        require(stockLines.any { Regex("^\\s*(?:return-object|throw)\\b").containsMatchIn(it) }) {
            "unsupported generateKeyPair stock fallback: no exit"
        }
    }

    // ==================================================================
    // AndroidKeyStoreSpi — certificate chain
    // ==================================================================

    private fun patchCertificateChain(content: String): Pair<String, Boolean> {
        val anchor = Regex(
            "(?m)^\\.method[^\\n]* engineGetCertificateChain\\(Ljava/lang/String;\\)" +
                "\\[Ljava/security/cert/Certificate;[ \\t]*$"
        ).find(content) ?: error("engineGetCertificateChain anchor method not found in KeyStoreSpi")
        val start = anchor.range.first
        val end = content.indexOf(".end method", start).also {
            require(it != -1) { "unterminated engineGetCertificateChain method in KeyStoreSpi" }
        }

        val methodBody = content.substring(start, end)
        val debugGap = "(?:\\s*\\.(?:line|local|end local|restart local|param)[^\\n]*\\n|\\s*\\n)*\\s*"

        val regMatch = REGISTER_DIRECTIVE.find(methodBody)
            ?: error("engineGetCertificateChain register directive not found")
        val regCount = regMatch.groupValues[2].toInt()
        val paramBase = if (regMatch.groupValues[1] == "registers") regCount - 2 else regCount
        require(paramBase + 1 <= 15) {
            "engineGetCertificateChain: p0/p1 exceed non-range invoke limit"
        }

        fun physical(register: String): Int =
            register.drop(1).toInt() + if (register.startsWith("p")) paramBase else 0

        if (CHAIN_ONE_ARG in methodBody || CHAIN_THREE_ARG in methodBody) {
            val call = Regex(
                "(?<indent>[ \\t]*)invoke-static(?:/range)?\\s*\\{(?<args>[^}]*)\\}\\s*,\\s*" +
                    "Landroid/security/kaorios/KaoriosHook;->CertificateChainIfNeeded" +
                    "\\([^)]*\\)\\[Ljava/security/cert/Certificate;"
            )
            val calls = call.findAll(methodBody).toList()
            require(calls.size == 1) {
                "engineGetCertificateChain: expected one covered chain hook, found ${calls.size}"
            }
            val match = calls[0]
            val args = match.groups["args"]!!.value.split(",").map { it.trim() }
            val already: Boolean
            when (args.size) {
                3 -> {
                    require(args[0] == "p0" && args[1] == "p1") {
                        "engineGetCertificateChain: unexpected three-argument hook registers"
                    }
                    already = true
                }
                1 -> already = false
                else -> error("engineGetCertificateChain: unsupported chain hook registers")
            }
            val array = args.last()
            require(Regex("[vp]\\d+").matches(array) && physical(array) <= 15) {
                "engineGetCertificateChain: chain register exceeds non-range invoke limit"
            }

            // Hook phải nuôi chính mảng trả về; hook cũ từng bỏ kết quả nên
            // đích của move-result cũng được chuẩn hoá.
            val pair = Regex(
                Regex.escape("invoke-static {p0, p1, $array}, $CHAIN_THREE_ARG") +
                    debugGap + "move-result-object\\s+(?<result>[vp]\\d+)" +
                    debugGap + "return-object\\s+(?<ret>[vp]\\d+)"
            )
            if (already) {
                val found = pair.find(methodBody)
                    ?: error("engineGetCertificateChain: three-argument hook is not at the return boundary")
                require(found.groups["ret"]!!.value == array) {
                    "engineGetCertificateChain: three-argument hook is not at the return boundary"
                }
                val result = found.groups["result"]!!
                if (result.value != array) {
                    val repaired = methodBody.substring(0, result.range.first) + array +
                        methodBody.substring(result.range.last + 1)
                    return content.substring(0, start) + repaired + content.substring(end) to true
                }
                return content to false
            }
            val invoke = "invoke-static {p0, p1, $array}, $CHAIN_THREE_ARG"
            val newBody = methodBody.substring(0, match.range.first) +
                match.groups["indent"]!!.value + invoke +
                methodBody.substring(match.range.last + 1)
            val found = pair.find(newBody)
                ?: error("engineGetCertificateChain: legacy hook does not dominate its return")
            var repaired = newBody
            if (found.groups["ret"]!!.value != array) {
                error("engineGetCertificateChain: legacy hook does not dominate its return")
            }
            val result = found.groups["result"]!!
            if (result.value != array) {
                repaired = repaired.substring(0, result.range.first) + array +
                    repaired.substring(result.range.last + 1)
            }
            return content.substring(0, start) + repaired + content.substring(end) to true
        }

        // Method thật (A13–A17) có hai null-return và một return mảng đã nạp.
        // Chèn ở leaf ngay trước return đó; aput-object trong vòng lặp không an toàn.
        val pattern = Regex(
            "(?<aput>aput-object\\s+[vp]\\d+,\\s*(?<array>[vp]\\d+),\\s*[vp]\\d+)" +
                "(?<gap>(?:[ \\t]*\\.(?:line|local|end local|restart local)[^\\n]*\\n|[ \\t]*\\n)*)" +
                "[ \\t]*(?<ret>return-object\\s+\\k<array>)"
        )
        val matches = pattern.findAll(methodBody).toList()
        require(matches.isNotEmpty()) { "engineGetCertificateChain: leaf-array return not found" }

        val returns = Regex("\\breturn-object\\s+([vp]\\d+)").findAll(methodBody)
            .map { it.groupValues[1] }.toList()
        if (returns.size == 3 && matches.size == 1) {
            val nullReg = returns[0]
            val arrayReg = matches[0].groups["array"]!!.value
            require(returns == listOf(nullReg, arrayReg, nullReg)) {
                "engineGetCertificateChain: unsupported null-return layout"
            }
            require(
                Regex("const/4\\s+$nullReg,\\s*0x0\\b").containsMatchIn(methodBody.substring(0, matches[0].range.first))
            ) { "engineGetCertificateChain: unsupported null-return layout" }
        } else {
            require(returns.size == matches.size) {
                "engineGetCertificateChain: unsupported return layout"
            }
        }

        var newBody = methodBody
        for (match in matches.reversed()) {
            val array = match.groups["array"]!!.value
            require(physical(array) <= 15) {
                "engineGetCertificateChain: chain register exceeds non-range invoke limit"
            }
            val inject = "invoke-static {p0, p1, $array}, $CHAIN_THREE_ARG\n    move-result-object $array\n    "
            val retStart = match.groups["ret"]!!.range.first
            newBody = newBody.substring(0, retStart) + inject + newBody.substring(retStart)
        }
        return content.substring(0, start) + newBody + content.substring(end) to true
    }

    fun patchSpi(content: String, update: Boolean = false): Pair<String, Boolean> {
        val (chainPatched, chainChanged) = patchCertificateChain(content)

        val signature = "engineGetCertificate(Ljava/lang/String;)Ljava/security/cert/Certificate;"
        val anchor = Regex("(?m)^\\.method[^\\n]* " + Regex.escape(signature) + "[ \\t]*$")
            .find(chainPatched) ?: error("engineGetCertificate anchor method not found in KeyStoreSpi")
        val start = anchor.range.first
        val end = chainPatched.indexOf(".end method", start).also {
            require(it != -1) { "unterminated engineGetCertificate method in KeyStoreSpi" }
        }

        val body = chainPatched.substring(start, end)
        if ("->engineGetCertificateChain(Ljava/lang/String;)[Ljava/security/cert/Certificate;" in body) {
            verifySpi(chainPatched)
            return chainPatched to chainChanged
        }

        val directive = REGISTER_DIRECTIVE.find(body)
            ?: error("engineGetCertificate register directive not found")
        val localsCount = directive.groupValues[2].toInt() -
            (if (directive.groupValues[1] == "registers") 2 else 0)
        require(localsCount >= 2) { "engineGetCertificate requires two existing local registers" }

        val label = SmaliOps.uniqueLabel(":kaorios_certificate_stock", body)
        val inject = "\n    invoke-virtual/range {p0 .. p1}, " +
            "Landroid/security/keystore2/AndroidKeyStoreSpi;->engineGetCertificateChain" +
            "(Ljava/lang/String;)[Ljava/security/cert/Certificate;\n" +
            "    move-result-object v0\n" +
            "    if-eqz v0, $label\n" +
            "    array-length v1, v0\n" +
            "    if-eqz v1, $label\n" +
            "    const/4 v1, 0x0\n" +
            "    aget-object v0, v0, v1\n" +
            "    return-object v0\n" +
            "    $label\n"

        val newBody = body.substring(0, directive.range.last + 1) + inject + body.substring(directive.range.last + 1)
        val patched = chainPatched.substring(0, start) + newBody + chainPatched.substring(end)
        return patched to true
    }

    fun verifySpi(content: String) {
        val body = extractMethodBody(
            content,
            "engineGetCertificateChain(Ljava/lang/String;)[Ljava/security/cert/Certificate;",
            "AndroidKeyStoreSpi"
        )
        val returns = Regex("return-object\\s+([vp]\\d+)").findAll(body).map { it.groupValues[1] }.toList()
        val returnCount = returns.size
        val hookCount = Regex(Regex.escape("KaoriosHook;->CertificateChainIfNeeded")).findAll(body).count()

        require(returnCount != 0) {
            "AndroidKeyStoreSpi: no return-object found in engineGetCertificateChain"
        }
        val expectedHooks = if (returnCount == 3) 1 else returnCount
        require(hookCount == expectedHooks) {
            "AndroidKeyStoreSpi: expected $expectedHooks CertificateChainIfNeeded hooks, found $hookCount"
        }
        require(CHAIN_ONE_ARG !in body) {
            "AndroidKeyStoreSpi: legacy one-argument chain hook must be updated"
        }

        val pairs = Regex(
            "aput-object\\s+[vp]\\d+,\\s*(?<array>[vp]\\d+),\\s*[vp]\\d+" +
                "(?:\\s*\\.(?:line|local|end local|restart local)[^\\n]*\\n|\\s*\\n)*\\s*" +
                "invoke-static\\s*\\{p0, p1, \\k<array>\\},\\s*" +
                Regex.escape(CHAIN_THREE_ARG) +
                "(?:\\s*\\.(?:line|local|end local|restart local)[^\\n]*\\n|\\s*\\n)*\\s*" +
                "move-result-object\\s+\\k<array>" +
                "(?:\\s*\\.(?:line|local|end local|restart local)[^\\n]*\\n|\\s*\\n)*\\s*" +
                "return-object\\s+\\k<array>"
        ).findAll(body).toList()

        require(pairs.size == hookCount) {
            "AndroidKeyStoreSpi: dataflow mismatch; hook must directly dominate its return"
        }
        if (returnCount == 3) {
            val nullReg = returns[0]
            require(returns == listOf(nullReg, pairs[0].groups["array"]!!.value, nullReg)) {
                "AndroidKeyStoreSpi: unsupported null-return layout"
            }
            require(Regex("const/4\\s+$nullReg,\\s*0x0\\b").containsMatchIn(body.substring(0, pairs[0].range.first))) {
                "AndroidKeyStoreSpi: unsupported null-return layout"
            }
        } else {
            require(returnCount == hookCount) {
                "AndroidKeyStoreSpi: unsupported return layout"
            }
        }

        var leafBody = extractMethodBody(
            content, "engineGetCertificate(Ljava/lang/String;)Ljava/security/cert/Certificate;", "AndroidKeyStoreSpi"
        )
        leafBody = Regex("(?m)^[ \\t]*\\.(?:line|local|end local|restart local|param)[^\\n]*\\n")
            .replace(leafBody, "")
        require(
            Regex(
                "invoke-virtual/range \\{p0 \\.\\. p1\\}, " +
                    "Landroid/security/keystore2/AndroidKeyStoreSpi;->engineGetCertificateChain" +
                    "\\(Ljava/lang/String;\\)\\[Ljava/security/cert/Certificate;\\s+" +
                    "move-result-object v0\\s+if-eqz v0, (?<label>:[\\w]+)\\s+" +
                    "array-length v1, v0\\s+if-eqz v1, \\k<label>\\s+" +
                    "const/4 v1, 0x0\\s+aget-object v0, v0, v1\\s+return-object v0\\s+\\k<label>"
            ).containsMatchIn(leafBody)
        ) {
            "AndroidKeyStoreSpi: single certificate must delegate to the chain with stock fallback"
        }
    }

    // ==================================================================
    // Helpers
    // ==================================================================

    fun extractMethodBody(content: String, methodAnchor: String, label: String): String {
        val anchor = Regex("(?m)^\\.method[^\\n]* " + Regex.escape(methodAnchor) + "[ \\t]*$").find(content)
            ?: error("$label: method anchor '$methodAnchor' not found")
        val start = anchor.range.first
        val end = content.indexOf(".end method", start).also {
            require(it != -1) { "$label: unterminated method at '$methodAnchor'" }
        }
        return content.substring(start, end)
    }

    /** Vị trí instruction đầu tiên sau metadata tham số và annotation. */
    private fun generatorEntryOffset(methodBody: String, directiveEnd: Int): Int {
        var offset = directiveEnd
        var annotationDepth = 0

        var position = directiveEnd
        while (position < methodBody.length) {
            val newlineAt = methodBody.indexOf('\n', position)
            val lineEnd = if (newlineAt < 0) methodBody.length else newlineAt + 1
            val stripped = methodBody.substring(position, lineEnd).trim()

            when {
                stripped.startsWith(".annotation ") || stripped.startsWith(".subannotation ") ->
                    annotationDepth++
                stripped.startsWith(".end annotation") || stripped.startsWith(".end subannotation") ->
                    annotationDepth--
                annotationDepth == 0 && stripped.isNotEmpty() &&
                    !stripped.startsWith("#") && !stripped.startsWith(".param ") &&
                    !stripped.startsWith(".end param") && !stripped.startsWith(".line ") &&
                    !stripped.startsWith(".local ") && !stripped.startsWith(".end local") &&
                    !stripped.startsWith(".restart local") && !stripped.startsWith(".prologue") &&
                    !stripped.startsWith(".epilogue") -> {
                    require(!stripped.startsWith(".")) { "unsupported generateKeyPair metadata: $stripped" }
                    require(!stripped.startsWith(":")) {
                        "unsupported generateKeyPair entry label: hook could be bypassed"
                    }
                    return offset
                }
            }
            offset = lineEnd
            position = lineEnd
        }
        error("generateKeyPair stock instructions not found")
    }

    /** Chỉ gỡ block hook cũ nếu nó thực sự sở hữu local cuối cùng. */
    internal fun removeLegacyRegisterHook(body: String, pattern: Regex, paramCount: Int): String {
        val directive = Regex("\\.(registers|locals)\\s+(\\d+)").find(body)
        val blocks = pattern.findAll(body).toList()
        require(directive != null && blocks.size == 1 && directive.groupValues[2].toInt() >= 1) {
            "unsupported update: no single recognized legacy hook block"
        }
        val block = blocks[0]
        require(block.range.first >= directive!!.range.last + 1) {
            "unsupported update: legacy hook precedes register directive"
        }
        val declared = directive.groupValues[2].toInt()
        val scratch = declared - (if (directive.groupValues[1] == "registers") paramCount else 0) - 1

        val registerUse = Regex(
            "(?m)^\\s*(?:move-result-object|move-result|return-object|return|if-eqz)\\s+([vp]\\d+)"
        ).findAll(block.value).map { it.groupValues[1] }.toSet()
        require(registerUse.isNotEmpty() && registerUse == setOf("v$scratch")) {
            "unsupported update: legacy hook does not own the last local"
        }

        val labels = Regex("(?m)^\\s*(:[\\w$]+)\\s*$").findAll(block.value).map { it.groupValues[1] }.toList()
        val branch = Regex("if-eqz\\s+[vp]\\d+,\\s*(:[\\w$]+)").find(block.value)
        require(labels.size == 1 && branch != null && branch.groupValues[1] == labels[0]) {
            "unsupported update: unrecognized legacy stock fallback"
        }

        val remainder = body.substring(0, block.range.first) + body.substring(block.range.last + 1)
        val instructions = SmaliOps.stripData(remainder)
        require(!Regex(Regex.escape(labels[0]) + "(?![\\w$])").containsMatchIn(instructions)) {
            "unsupported update: legacy fallback label is referenced by stock code"
        }
        require(!Regex("(?<![\\w/$;>:])v$scratch(?![\\w/$;])").containsMatchIn(instructions)) {
            "unsupported update: legacy scratch is referenced by stock code"
        }

        return remainder.substring(0, directive.range.first) +
            ".${directive.groupValues[1]} ${declared - 1}" +
            remainder.substring(directive.range.last + 1)
    }
}
