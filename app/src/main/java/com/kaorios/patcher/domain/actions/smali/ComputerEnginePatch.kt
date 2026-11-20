package com.kaorios.patcher.domain.actions.smali

/**
 * Port của `patch-services-a17.py`.
 *
 * Chèn hook `shouldHideAppListForCaller(I, String, I)Z` vào đầu
 * `ComputerEngine.shouldFilterApplication(...)`.
 *
 * Có hai layout được hỗ trợ:
 * 1. Đủ thanh ghi cho hook "phẳng" dùng v0 (sau khi bump `.locals`/`.registers` lên 1).
 * 2. Không đủ thanh ghi → chuyển toàn bộ sang `.locals`, dịch tham số về `pN`,
 *    rồi dựng frame high-register với vùng scratch riêng.
 *
 * Cả hai đường đều kết thúc bằng `verifyVisibility()` — regex cấu trúc chính xác,
 * không chấp nhận biến thể.
 */
object ComputerEnginePatch {

    private const val HOOK = "Landroid/security/kaorios/KaoriosHook;->shouldHideAppListForCaller(ILjava/lang/String;I)Z"
    private const val GET_PACKAGE_NAME_CALL =
        "invoke-interface {p1}, Lcom/android/server/pm/pkg/PackageStateInternal;->getPackageName()Ljava/lang/String;"

    private const val LABEL = ":cond_kaorios_ps_null"

    private val METHOD_7_PARAM_RE = Regex(
        "(?m)^\\.method[^\\r\\n]*[ \\t]" +
            "shouldFilterApplication" +
            "\\(Lcom/android/server/pm/pkg/PackageStateInternal;ILandroid/content/ComponentName;IIZZ\\)Z" +
            "[ \\t]*(?:\\r?\\n|$)"
    )
    private val METHOD_3_PARAM_RE = Regex(
        "(?m)^\\.method[^\\r\\n]*[ \\t]" +
            "shouldFilterApplication" +
            "\\(Lcom/android/server/pm/pkg/PackageStateInternal;II\\)Z" +
            "[ \\t]*(?:\\r?\\n|$)"
    )
    private val METHOD_END_RE = Regex("(?m)^[ \\t]*\\.end method[ \\t]*(?:\\r?\\n|$)")
    private val REGISTERS_RE = Regex("(?m)^(?<indent>[ \\t]*)\\.registers[ \\t]+(?<num>\\d+)[ \\t]*(?:\\r?\\n|$)")
    private val LOCALS_RE = Regex("(?m)^(?<indent>[ \\t]*)\\.locals[ \\t]+(?<num>\\d+)[ \\t]*(?:\\r?\\n|$)")
    private val HOOK_RE = Regex(Regex.escape(HOOK))
    private val DEBUG_DIRECTIVE_RE =
        Regex("(?m)^[ \\t]*\\.(?:line|local|end local|restart local|prologue|epilogue)\\b[^\\n]*\\n")

    private data class Span(val start: Int, val end: Int, val paramCount: Int)

    private fun methodSpan(text: String): Span {
        val matches7 = METHOD_7_PARAM_RE.findAll(text).toList()
        if (matches7.size == 1) {
            val end = METHOD_END_RE.find(text, matches7[0].range.last + 1)
                ?: error("unterminated ComputerEngine.shouldFilterApplication (7-param)")
            return Span(matches7[0].range.first, end.range.last + 1, 7)
        }

        val matches3 = METHOD_3_PARAM_RE.findAll(text).toList()
        if (matches3.size == 1) {
            val end = METHOD_END_RE.find(text, matches3[0].range.last + 1)
                ?: error("unterminated ComputerEngine.shouldFilterApplication (3-param)")
            return Span(matches3[0].range.first, end.range.last + 1, 3)
        }

        if (matches7.size > 1 || matches3.size > 1) {
            error(
                "ambiguous shouldFilterApplication: found ${matches7.size} (7-param) " +
                    "and ${matches3.size} (3-param)"
            )
        }
        error("target ComputerEngine.shouldFilterApplication method not found")
    }

    private fun hookCount(body: String): Int = HOOK_RE.findAll(body).count()

    /** Dựng frame hook khi không đủ thanh ghi 4-bit cho cách phẳng. */
    private fun highHook(paramCount: Int, oldBase: Int, scratch: Int, label: String, newline: String): String {
        val width = if (paramCount == 7) 8 else 4
        val user = if (paramCount == 7) 5 else 3
        val objects = if (paramCount == 7) setOf(0, 1, 3) else setOf(0, 1)

        val lines = mutableListOf<String>()
        for (i in 0 until width) {
            val suffix = if (i in objects) "-object" else ""
            lines += "move$suffix/16 v${oldBase + i}, p$i"
        }
        lines += listOf(
            "if-eqz v${oldBase + 1}, $label",
            "invoke-interface/range {v${oldBase + 1} .. v${oldBase + 1}}, " +
                "Lcom/android/server/pm/pkg/PackageStateInternal;->getPackageName()Ljava/lang/String;",
            "move-result-object v${scratch + 1}",
            "if-eqz v${scratch + 1}, $label",
            "move/16 v$scratch, v${oldBase + 2}",
            "move/16 v${scratch + 2}, v${oldBase + user}",
            "invoke-static/range {v$scratch .. v${scratch + 2}}, $HOOK",
            "move-result v$scratch",
            "if-eqz v$scratch, $label",
            "const/16 v$scratch, 0x1",
            "return v$scratch",
            label
        )
        return lines.joinToString("") { "    $it$newline" }
    }

    fun verifyVisibility(text: String) {
        val span = methodSpan(text)
        var body = text.substring(span.start, span.end)
        body = DEBUG_DIRECTIVE_RE.replace(body, "")

        val count = hookCount(body)
        require(count == 1) { "expected exactly one shouldHideAppListForCaller hook; found $count" }

        if (GET_PACKAGE_NAME_CALL !in body) {
            // Nhánh high-register: kiểm tra ngược frame từ directive.
            val localsDirective = LOCALS_RE.find(body)
            val registersDirective = REGISTERS_RE.find(body)
            val directive = localsDirective ?: registersDirective
            require(directive != null) { "missing registers for high-register hook" }
            val isRegisters = localsDirective == null
            val width = if (span.paramCount == 7) 8 else 4
            val declared = directive!!.groups["num"]!!.value.toInt()
            val localsCount = declared - (if (isRegisters) width else 0)
            val scratch = localsCount - 3
            val oldBase = scratch - width

            require(oldBase >= 0 && scratch + 2 <= 255) { "invalid high-register hook frame" }
            val labelMatch = Regex("if-eqz\\s+v${oldBase + 1},\\s*(:\\S+)").find(body)
            require(labelMatch != null) { "invalid high-register hook frame" }
            val expected = highHook(span.paramCount, oldBase, scratch, labelMatch!!.groupValues[1], "\n")
            require(compact(expected) in compact(body)) {
                "incorrect high-register caller/package/user or return sequence"
            }
            return
        }

        val userParam = if (span.paramCount == 7) "p5" else "p3"

        val pattern = Regex(
            "if-eqz\\s+p1,\\s*(:\\S+)\\s*(?:\\r?\\n)+" +
                "\\s*" + Regex.escape(GET_PACKAGE_NAME_CALL) +
                "\\s*(?:\\r?\\n)+" +
                "\\s*move-result-object\\s+(v\\d+)\\s*(?:\\r?\\n)+" +
                "\\s*if-eqz\\s+\\2,\\s*\\1\\s*(?:\\r?\\n)+" +
                "\\s*invoke-static\\s*\\{p2,\\s*\\2,\\s*" + userParam + "\\},\\s*" +
                Regex.escape(HOOK) +
                "\\s*(?:\\r?\\n)+" +
                "\\s*move-result\\s+(v\\d+)\\s*(?:\\r?\\n)+" +
                "\\s*if-eqz\\s+\\3,\\s*\\1\\s*(?:\\r?\\n)+" +
                "\\s*const/4\\s+\\3,\\s*0x1\\s*(?:\\r?\\n)+" +
                "\\s*return\\s+\\3\\s*(?:\\r?\\n)+" +
                "\\s*\\1"
        )
        require(pattern.containsMatchIn(body)) {
            "hook sequence does not match exact fail-closed return structure or register order"
        }
    }

    private fun compact(value: String): String =
        value.split("\n").map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")

    /** Vị trí chèn: sau `.registers`/`.locals`, `.param`, và header `.annotation`. */
    private fun findInjectionPoint(body: String): Int {
        var injectedOffset = 0
        var inAnnotation = false
        var position = 0

        while (position < body.length) {
            val newlineAt = body.indexOf('\n', position)
            val lineEnd = if (newlineAt < 0) body.length else newlineAt + 1
            val stripped = body.substring(position, lineEnd).trim()

            when {
                stripped.isEmpty() -> Unit
                stripped.startsWith(".method") -> Unit
                stripped.startsWith(".registers") || stripped.startsWith(".locals") -> {
                    injectedOffset = lineEnd
                }
                stripped.startsWith(".param") -> {
                    injectedOffset = lineEnd
                }
                stripped.startsWith(".annotation") -> {
                    inAnnotation = true
                    injectedOffset = lineEnd
                }
                inAnnotation -> {
                    injectedOffset = lineEnd
                    if (stripped.startsWith(".end annotation")) inAnnotation = false
                }
                else -> return injectedOffset
            }
            position = lineEnd
        }
        return injectedOffset
    }

    fun patchVisibility(text: String): Pair<String, Boolean> {
        val span = methodSpan(text)
        val body = text.substring(span.start, span.end)
        val count = hookCount(body)

        if (count == 1) {
            verifyVisibility(text)
            return text to false
        }
        require(count <= 1) { "multiple shouldHideAppListForCaller hooks already present" }
        require(LABEL !in body) { "reserved Kaorios label already exists in target method" }

        val newline = if ("\r\n" in text) "\r\n" else "\n"
        val userParam = if (span.paramCount == 7) "p5" else "p3"
        val paramWidth = if (span.paramCount == 7) 8 else 4

        val regMatch = REGISTERS_RE.find(body)
        val locMatch = LOCALS_RE.find(body)
        var updatedBody = body
        var hookReg: String
        var newLocals: Int

        when {
            locMatch != null -> {
                val currentLocs = locMatch.groups["num"]!!.value.toInt()
                updatedBody = SmaliOps.canonicalizeParamAliasesAtBase(body, currentLocs, paramWidth)
                newLocals = currentLocs + 1
                hookReg = "v$currentLocs"
                val indent = locMatch.groups["indent"]!!.value
                updatedBody = updatedBody.substring(0, locMatch.range.first) +
                    "$indent.locals $newLocals$newline" +
                    updatedBody.substring(locMatch.range.last + 1)
            }

            regMatch != null -> {
                val currentRegs = regMatch.groups["num"]!!.value.toInt()
                val existingLocals = currentRegs - paramWidth
                require(existingLocals >= 0) {
                    ".registers $currentRegs is less than parameter count $paramWidth"
                }
                // `.registers` có thể tham chiếu slot tham số bằng vN. Thêm local sẽ dịch
                // thanh ghi vật lý, nên chuẩn hoá alias về pN trước khi đổi sang `.locals`.
                updatedBody = SmaliOps.canonicalizeParamAliasesAtBase(body, existingLocals, paramWidth)
                newLocals = existingLocals + 1
                hookReg = "v$existingLocals"
                val indent = regMatch.groups["indent"]!!.value
                updatedBody = updatedBody.substring(0, regMatch.range.first) +
                    "$indent.locals $newLocals$newline" +
                    updatedBody.substring(regMatch.range.last + 1)
            }

            else -> {
                hookReg = "v0"
                newLocals = 1
                val firstLineEnd = updatedBody.indexOf('\n').let { if (it < 0) updatedBody.length else it + 1 }
                updatedBody = updatedBody.substring(0, firstLineEnd) +
                    "    .locals 1$newline" +
                    updatedBody.substring(firstLineEnd)
            }
        }

        val localsMatch = LOCALS_RE.find(updatedBody)
        val declaredLocals = localsMatch?.groups?.get("num")?.value?.toInt() ?: 1

        if (maxOf(declaredLocals + paramWidth - 1, hookReg.drop(1).toInt()) > 15) {
            // Không đủ thanh ghi 4-bit: dựng lại toàn bộ method quanh vùng scratch cao.
            val originalDirective = LOCALS_RE.find(body) ?: REGISTERS_RE.find(body)
            require(originalDirective != null) { "missing register directive for high-register hook" }
            val oldCount = originalDirective.groups["num"]!!.value.toInt()
            val isRegisters = REGISTERS_RE.matches(originalDirective.value)
            val oldBase = if (isRegisters) oldCount - paramWidth else oldCount
            val scratch = oldBase + paramWidth
            require(scratch + 2 <= 255) {
                "UNSUPPORTED_LAYOUT: high-register scratch exceeds 8-bit limits"
            }

            val rebuilt = buildString {
                body.lineSequence().forEach { line ->
                    if (line.trimStart().startsWith(".param")) {
                        append(line).append('\n')
                    } else {
                        // Thanh ghi trong string/comment là data, phải giữ nguyên byte.
                        val rewritten = SmaliOps.replaceInCode(line) { code ->
                            Regex("(?<![\\w/$;>:])p(\\d+)(?![\\w/$;])").replace(code) { m ->
                                "v${oldBase + m.groupValues[1].toInt()}"
                            }
                        }
                        append(rewritten).append('\n')
                    }
                }
            }

            val directive = LOCALS_RE.find(rebuilt) ?: REGISTERS_RE.find(rebuilt)
            require(directive != null) { "missing register directive after parameter rewrite" }
            val withDirective = rebuilt.substring(0, directive.range.first) +
                "    .locals ${scratch + 3}$newline" +
                rebuilt.substring(directive.range.last + 1)

            val injOffset = findInjectionPoint(withDirective)
            val hookCode = highHook(span.paramCount, oldBase, scratch, LABEL, newline)
            val patchedBody = withDirective.substring(0, injOffset) + hookCode + withDirective.substring(injOffset)
            val patched = text.substring(0, span.start) + patchedBody + text.substring(span.end)
            verifyVisibility(patched)
            return patched to true
        }

        val injOffset = findInjectionPoint(updatedBody)
        val hookCode = buildString {
            append("    if-eqz p1, $LABEL$newline")
            append("    $GET_PACKAGE_NAME_CALL$newline")
            append("    move-result-object $hookReg$newline")
            append("    if-eqz $hookReg, $LABEL$newline")
            append("    invoke-static {p2, $hookReg, $userParam}, $HOOK$newline")
            append("    move-result $hookReg$newline")
            append("    if-eqz $hookReg, $LABEL$newline")
            append("    const/4 $hookReg, 0x1$newline")
            append("    return $hookReg$newline")
            append("    $LABEL$newline")
        }

        val patchedBody = updatedBody.substring(0, injOffset) + hookCode + updatedBody.substring(injOffset)
        val patched = text.substring(0, span.start) + patchedBody + text.substring(span.end)
        verifyVisibility(patched)
        return patched to true
    }

    private val INSTALLER_METHOD = Regex(
        "(?m)^\\.method[^\\n]*\\b(?:getInstallerPackageName|getInstallSourceInfo)\\("
    )

    private fun hasInstallerMethods(text: String): Boolean = INSTALLER_METHOD.containsMatchIn(text)

    fun verify(text: String) {
        verifyVisibility(text)
        if (hasInstallerMethods(text)) InstallerSourcePatch.verify(text)
    }

    /** Chỉ `shouldFilterApplication` (hide app). */
    fun verifyVisibilityOnly(text: String) = verifyVisibility(text)

    /** Chỉ installer provenance (`InstallerSourcePatch`). */
    fun hasInstaller(text: String): Boolean = hasInstallerMethods(text)

    fun verifyInstallerOnly(text: String) = InstallerSourcePatch.verify(text)

    fun patch(text: String): Pair<String, Boolean> {
        var (patched, changed) = patchVisibility(text)
        if (hasInstallerMethods(text)) {
            val (installerPatched, installerChanged) = InstallerSourcePatch.patch(patched)
            patched = installerPatched
            changed = changed || installerChanged
        }
        verify(patched)
        return patched to changed
    }

    /** Chỉ patch `shouldFilterApplication` (hide app). */
    fun patchVisibilityOnly(text: String): Pair<String, Boolean> {
        val result = patchVisibility(text)
        verifyVisibility(result.first)
        return result
    }

    /** Chỉ patch installer provenance (spoof installed source). */
    fun patchInstallerOnly(text: String): Pair<String, Boolean> {
        require(hasInstallerMethods(text)) { "ComputerEngine: installer APIs not found" }
        val result = InstallerSourcePatch.patch(text)
        InstallerSourcePatch.verify(result.first)
        return result
    }
}
