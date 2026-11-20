package com.kaorios.patcher.domain.actions.smali

/**
 * Port của `patch_name_value_cache` từ `kaorios_patcher.py`.
 *
 * `Settings$NameValueCache.getStringForUser(ContentResolver, String, I)String`
 * nhận hook ở entry: hook trả về String thì trả luôn, trả null thì rơi xuống
 * logic gốc. Hook boolean ABI của bản cũ bị từ chối, không tự ý gỡ.
 */
object NameValueCachePatch {

    private const val METHOD =
        "getStringForUser(Landroid/content/ContentResolver;Ljava/lang/String;I)Ljava/lang/String;"
    private const val HOOK =
        "Landroid/security/kaorios/KaoriosHook;->shouldHideDevStatusFromNameValueCache" +
            "(Landroid/content/ContentResolver;Ljava/lang/String;I)Ljava/lang/String;"
    private const val LEGACY_HOOK_BOOL =
        "Landroid/security/kaorios/KaoriosHook;->shouldHideDevStatusFromNameValueCache" +
            "(Landroid/content/ContentResolver;Ljava/lang/String;I)Z"
    private const val LABEL = ":cond_kaorios_settings_stock"

    private val HEADER_FLAGS = Regex("\\b(static|abstract|native)\\b")
    private val REGISTER_DIRECTIVE = Regex("\\.(registers|locals)\\s+(\\d+)")
    private val LEGACY_HOOK_RE = Regex(
        "(?m)^\\s*invoke-static(?:/range)?[ \\t]+\\{[^}\\n]*\\},[ \\t]*" +
            Regex.escape(LEGACY_HOOK_BOOL) + "[ \\t]*(?:#.*)?$"
    )

    private data class Entry(val body: String, val registers: Int, val entry: Int)

    private fun entry(content: String): Entry {
        val body = KeystorePatch.extractMethodBody(content, METHOD, "Settings\$NameValueCache")
        val header = body.lineSequence().first()
        require(!HEADER_FLAGS.containsMatchIn(header)) { "unsupported NameValueCache method flags" }

        val directive = REGISTER_DIRECTIVE.find(body)
            ?: error("NameValueCache register directive not found")
        val registers = directive.groupValues[2].toInt() +
            (if (directive.groupValues[1] == "locals") 4 else 0)
        require(registers in 5..65535) {
            "unsupported NameValueCache register allocation: entry needs a local"
        }
        SmaliOps.verifyRegisterEncoding(body, registers, 4)
        val entryOffset = generatorEntryOffset(body, directive.range.last + 1, "NameValueCache")
        return Entry(body, registers, entryOffset)
    }

    fun patch(content: String, update: Boolean = false): Pair<String, Boolean> {
        val parsed = entry(content)
        val body = parsed.body

        if ("KaoriosHook;" in body) {
            // Hook dở dang hoặc lạ: không bao giờ gỡ đoán mò, kể cả khi update.
            verify(content)
            return content to false
        }

        val label = SmaliOps.uniqueLabel(LABEL, body)
        val inject = "\n    invoke-static/range {p1 .. p3}, $HOOK\n" +
            "    move-result-object v0\n" +
            "    if-eqz v0, $label\n" +
            "    return-object v0\n\n" +
            "    $label\n"
        val patched = body.substring(0, parsed.entry) + inject + body.substring(parsed.entry)
        return content.replaceFirst(body, patched) to true
    }

    fun verify(content: String) {
        val parsed = entry(content)
        val body = parsed.body

        require(!LEGACY_HOOK_RE.containsMatchIn(body)) {
            "NameValueCache: legacy boolean hook ABI; restore Settings\$NameValueCache " +
                "from the matching stock ROM before applying the current String hook; " +
                "--update preserves this input"
        }

        val sequence = Regex(
            "\\s*invoke-static/range \\{p1 \\.\\. p3\\}, " + Regex.escape(HOOK) +
                "\\s+move-result-object v0\\s+if-eqz v0, (?<label>:[\\w$]+)" +
                "\\s+return-object v0\\s+\\k<label>\\s*"
        ).find(body.substring(parsed.entry))
        require(sequence != null && body.split("KaoriosHook;").size - 1 == 1) {
            "NameValueCache: invalid entry hook or incomplete hook coverage"
        }
        val label = sequence!!.groups["label"]!!.value
        require(Regex("(?m)^\\s*${Regex.escape(label)}\\s*$").findAll(body).count() == 1) {
            "NameValueCache: stock fallback label is not unique"
        }
        val stock = body.substring(parsed.entry + sequence.range.last + 1)
        require(Regex("(?m)^\\s*(?:return-object|throw)\\b").containsMatchIn(stock)) {
            "unsupported NameValueCache stock fallback: no exit"
        }
    }

    /** Vị trí instruction đầu tiên sau metadata tham số và annotation. */
    private fun generatorEntryOffset(methodBody: String, directiveEnd: Int, label: String): Int {
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
                    require(!stripped.startsWith(".")) { "$label: unsupported metadata: $stripped" }
                    require(!stripped.startsWith(":")) { "$label: unsupported entry label before hook" }
                    return offset
                }
            }
            offset = lineEnd
            position = lineEnd
        }
        error("$label stock instructions not found")
    }
}
