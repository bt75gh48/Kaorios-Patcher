package com.kaorios.patcher.domain.actions.smali

/**
 * Port của `patch-installer-source.py`.
 *
 * Bọc kết quả "installer package name" bằng
 * `KaoriosHook.filterInstallerPackageName(ContentResolver, int, int, String, String)`.
 *
 * Điểm khó không nằm ở việc chèn hook mà ở chỗ phải CHỨNG MINH bằng phân tích
 * control-flow rằng giá trị sắp trả về thực sự chảy từ
 * `ComputerEngine.getInstallSource(...)` (legacy) hoặc từ constructor
 * `InstallSourceInfo` với tham số installer đúng vị trí (modern). Bất kỳ nhánh
 * không chứng minh được đều bị từ chối thay vì đoán.
 */
object InstallerSourcePatch {

    private const val HOOK =
        "Landroid/security/kaorios/KaoriosHook;->filterInstallerPackageName" +
            "(Landroid/content/ContentResolver;IILjava/lang/String;Ljava/lang/String;)Ljava/lang/String;"

    private const val INIT = "Landroid/content/pm/InstallSourceInfo;-><init>"

    private val METHOD = Regex(
        "(?m)^\\.method ([^\\n]+)\\n(.*?)^\\.end method",
        RegexOption.DOT_MATCHES_ALL
    )
    private val CLASS_RE = Regex("(?m)^\\s*\\.class [^\\n]*Lcom/android/server/pm/ComputerEngine;\\s*$")
    private val REGISTER_DIRECTIVE = Regex("(?m)^[ \\t]*\\.(locals|registers)[ \\t]+(\\d+)[ \\t]*$")
    private val LOCALS = Regex("(?m)^[ \\t]*\\.locals[ \\t]+(\\d+)[ \\t]*$")
    private val REGISTER_TOKEN = Regex("\\b[vp]\\d+\\b")
    private val WHITESPACE = Regex("\\s+")
    private val GET_INSTALL_SOURCE = "Lcom/android/server/pm/ComputerEngine;->getInstallSource("
    private val INSTALLER_FIELD = Regex(
        "Lcom/android/server/pm/InstallSource;->(?:mInstallerPackageName|installerPackageName):Ljava/lang/String;"
    )
    private val CONST_ZERO = Regex(",\\s*0x0$")
    private val STRING_LITERAL = Regex("\"(?:\\\\.|[^\"\\\\])*\"")

    private val UNKNOWN = setOf("unknown")

    private val SIGNATURES = setOf(
        "getInstallerPackageName(Ljava/lang/String;)Ljava/lang/String;",
        "getInstallerPackageName(Ljava/lang/String;I)Ljava/lang/String;",
        "getInstallSourceInfo(Ljava/lang/String;)Landroid/content/pm/InstallSourceInfo;",
        "getInstallSourceInfo(Ljava/lang/String;I)Landroid/content/pm/InstallSourceInfo;"
    )

    private val CONSTRUCTOR_ARITY = mapOf(
        "(Ljava/lang/String;Landroid/content/pm/SigningInfo;Ljava/lang/String;Ljava/lang/String;I)V" to 6,
        "(Ljava/lang/String;Landroid/content/pm/SigningInfo;Ljava/lang/String;Ljava/lang/String;" +
            "Ljava/lang/String;I)V" to 7
    )

    private data class Method(val header: String, val body: String, val start: Int, val end: Int, val raw: String)

    private data class Normalized(val body: String, val base: Int, val width: Int, val total: Int)

    // ==================================================================
    // Public API
    // ==================================================================

    fun patch(text: String): Pair<String, Boolean> {
        if (HOOK in text) {
            verify(text)
            return text to false
        }
        var result = text
        for (method in targetMethods(text).reversed()) {
            result = result.substring(0, method.start) + patchMethod(method) + result.substring(method.end)
        }
        verify(result)
        return result to true
    }

    fun verify(text: String) {
        val canonicalText = canonical(text)
        val targets = targetMethods(canonicalText)

        val hookCount = canonicalText.split(HOOK).size - 1
        require(hookCount == targets.sumOf { it.raw.split(HOOK).size - 1 }) {
            "installer hook outside supported read APIs"
        }

        for (method in targets) {
            val body = method.body
            val directive = LOCALS.find(body) ?: error("missing allocated locals")
            val scratch = directive.groupValues[1].toInt() - 5
            val width = if ("Ljava/lang/String;I)" in method.header) 3 else 2
            val base = scratch - width
            val prologue = prologue(base, width, scratch)

            require(body.split(prologue).size - 1 == 1) {
                "missing/altered original caller, target or user capture"
            }
            val first = executable(body).first().first
            require(body.substring(lineStartOffset(body, first)).startsWith(prologue)) {
                "caller capture must precede stock instructions and identity clear"
            }

            val stock = body.replaceFirst(prologue, "")
            val pattern = Regex(
                "    move-object/16 v${scratch + 4}, (?<real>[vp]\\d+)\\n" +
                    "    invoke-static/range \\{v$scratch \\.\\. v${scratch + 4}\\}, " +
                    Regex.escape(HOOK) + "\\n" +
                    "    move-result-object \\k<real>\\n"
            )
            val blocks = pattern.findAll(stock).count()
            require(blocks > 0 && blocks == stock.split(HOOK).size - 1) {
                "partial or duplicate installer hook"
            }

            var strippedStock = pattern.replace(stock, "")
            val locals = LOCALS.find(strippedStock) ?: error("missing allocated locals")
            strippedStock = strippedStock.substring(0, locals.range.first) +
                "    .registers $scratch\n" +
                strippedStock.substring(locals.range.last + 1)

            val rebuilt = ".method ${method.header}\n$strippedStock.end method"
            val candidate = parse(rebuilt).singleOrNull()
                ?: error("hook coverage/position/value differs from proven stock return paths")
            require(canonical(patchMethod(candidate)) == canonical(method.raw)) {
                "hook coverage/position/value differs from proven stock return paths"
            }
        }
    }

    // ==================================================================
    // Method selection
    // ==================================================================

    private fun parse(text: String): List<Method> =
        METHOD.findAll(text).map { match ->
            Method(
                header = match.groupValues[1],
                body = match.groupValues[2],
                start = match.range.first,
                end = match.range.last + 1,
                raw = match.value
            )
        }.toList()

    private fun targetMethods(text: String): List<Method> {
        require(CLASS_RE.containsMatchIn(text)) { "UNSUPPORTED_LAYOUT: expected ComputerEngine class" }
        val matches = parse(text).filter { it.header.split(WHITESPACE).last() in SIGNATURES }
        require(
            matches.size == 2 &&
                matches.map { it.header.split(WHITESPACE).last().substringBefore('(') }.toSet().size == 2
        ) { "UNSUPPORTED_LAYOUT: require both installer APIs exactly once" }
        return matches
    }

    // ==================================================================
    // Normalization
    // ==================================================================

    /** Dịch `pN` về đúng slot vật lý cũ `v{base+N}` để bump local không làm lệch tham số. */
    private fun normalize(method: Method): Normalized {
        val header = method.header
        require("static" !in header.split(WHITESPACE)) { "UNSUPPORTED_LAYOUT: static API" }
        val width = if ("Ljava/lang/String;I)" in header) 3 else 2

        val matches = REGISTER_DIRECTIVE.findAll(method.body).toList()
        require(matches.size == 1) { "UNSUPPORTED_LAYOUT: register directive" }
        val directive = matches[0]
        val count = directive.groupValues[2].toInt()
        val base = if (directive.groupValues[1] == "locals") count else count - width
        val total = base + width
        require(base >= 0 && total + 4 <= 255) { "UNSUPPORTED_LAYOUT: register limits" }

        // `splitlines()` của Python bỏ newline cuối; giữ đúng một newline khi ghép lại.
        val lines = method.body.trimEnd('\n').split("\n").toMutableList()
        for (index in lines.indices) {
            val line = lines[index]
            if (line.trim().startsWith(".param")) continue

            val commentAt = line.indexOf('#')
            val instruction = if (commentAt < 0) line else line.substring(0, commentAt)
            val suffix = if (commentAt < 0) "" else line.substring(commentAt)

            val rewritten = SmaliOps.replaceInCode(instruction) { code ->
                Regex("\\bp(\\d+)\\b").replace(code) { m ->
                    "v${base + m.groupValues[1].toInt()}"
                }
            }
            lines[index] = rewritten + suffix
        }
        return Normalized(lines.joinToString("\n") + "\n", base, width, total)
    }

    // ==================================================================
    // Provenance analysis
    // ==================================================================

    private fun executable(body: String): List<Pair<Int, String>> =
        body.split("\n").mapIndexedNotNull { index, line ->
            val stripped = line.substringBefore('#').trim()
            if (stripped.isEmpty() || stripped.startsWith(".")) null else index to stripped
        }

    private fun registers(invocation: String): List<String> {
        val open = invocation.indexOf('{')
        val close = invocation.indexOf('}', startIndex = if (open < 0) 0 else open + 1)
        require(open >= 0 && close > open) { "UNSUPPORTED_LAYOUT: register list" }

        val raw = invocation.substring(open + 1, close).trim()
        if (raw.isEmpty()) return emptyList()
        if (".." in raw) {
            val bounds = raw.split("..").map { it.trim() }
            require(bounds.size == 2 && bounds[0].isNotEmpty() && bounds[1].isNotEmpty()) {
                "UNSUPPORTED_LAYOUT: mixed register range"
            }
            val first = bounds[0]
            val last = bounds[1]
            require(first[0] == last[0]) { "UNSUPPORTED_LAYOUT: mixed register range" }
            return (first.drop(1).toInt()..last.drop(1).toInt()).map { first[0] + it.toString() }
        }
        return raw.split(",").map { it.trim() }
    }

    /**
     * Tập vị trí "sites" (dòng → thanh ghi) mà tại đó giá trị installer đã được
     * chứng minh nguồn gốc.
     */
    private fun stockSites(body: String, base: Int, width: Int, modern: Boolean): Map<Int, String> {
        val code = executable(body)
        require(
            code.none { (_, s) ->
                "switch" in s || s.startsWith("fill-array") || s.startsWith(".catch")
            } && ".catch" !in body
        ) { "UNSUPPORTED_LAYOUT: exceptional/switch control flow" }

        val labels = mutableMapOf<String, Int>()
        code.forEachIndexed { n, (_, s) -> if (s.startsWith(":")) labels[s] = n }

        val initial = mutableMapOf(
            "v$base" to setOf("receiver"),
            "v${base + 1}" to setOf("target")
        )
        if (width == 3) initial["v${base + 2}"] = setOf("user")

        val states = mutableMapOf(0 to initial)
        val queue = ArrayDeque(listOf(0))
        val sites = mutableMapOf<Int, String>()

        while (queue.isNotEmpty()) {
            val n = queue.removeLast()
            val env = states.getValue(n).toMutableMap()
            val (lineNo, instruction) = code[n]
            val regs = REGISTER_TOKEN.findAll(instruction.split(", L")[0]).map { it.value }.toList()

            fun value(register: String): Set<String> = env[register] ?: UNKNOWN

            when {
                instruction.startsWith("invoke-") -> {
                    val args = registers(instruction)
                    val target = instruction.substringAfter("},").trim()
                    env["\$result"] = UNKNOWN

                    when {
                        target == "Landroid/os/Binder;->getCallingUid()I" -> {
                            require(env["\$cleared"] == null) {
                                "UNSUPPORTED_LAYOUT: UID read after identity clear"
                            }
                            env["\$result"] = setOf("uid")
                        }

                        target == "Landroid/os/Binder;->clearCallingIdentity()J" -> {
                            env["\$cleared"] = setOf("yes")
                        }

                        target.startsWith(GET_INSTALL_SOURCE) -> {
                            val expected = listOf("receiver", "target", "uid") +
                                (if (width == 3) listOf("user") else emptyList())
                            require(args.size == expected.size) {
                                "UNSUPPORTED_LAYOUT: stock source caller/target/user provenance"
                            }
                            for (index in expected.indices) {
                                require(value(args[index]) == setOf(expected[index])) {
                                    "UNSUPPORTED_LAYOUT: stock source caller/target/user provenance"
                                }
                            }
                            env["\$result"] = setOf("source")
                        }

                        target.startsWith(INIT) -> {
                            val descriptor = target.substring(INIT.length)
                            require(modern && CONSTRUCTOR_ARITY[descriptor] == args.size) {
                                "UNSUPPORTED_LAYOUT: InstallSourceInfo constructor"
                            }
                            val installer = value(args[4])
                            require("installer" in installer && installer.all { it == "installer" || it == "null" }) {
                                "UNSUPPORTED_LAYOUT: installing argument is not stock installer"
                            }
                            sites[lineNo] = args[4]

                            val receiver = value(args[0])
                            require(receiver.size == 1 && receiver.first().startsWith("new:")) {
                                "UNSUPPORTED_LAYOUT: constructor receiver"
                            }
                            for ((register, tags) in env.entries.toList()) {
                                if (tags == receiver) env[register] = setOf("info")
                            }
                        }
                    }
                }

                instruction.startsWith("move-result") -> {
                    require(regs.isNotEmpty()) { "UNSUPPORTED_LAYOUT: move-result without register" }
                    env[regs[0]] = env.remove("\$result") ?: UNKNOWN
                }

                instruction.startsWith("move") -> {
                    require(regs.size >= 2) { "UNSUPPORTED_LAYOUT: move without source" }
                    env[regs[0]] = value(regs[1])
                }

                instruction.startsWith("iget-object") && INSTALLER_FIELD.containsMatchIn(instruction) -> {
                    require(regs.size >= 2 && value(regs[1]) == setOf("source")) {
                        "UNSUPPORTED_LAYOUT: installer field source"
                    }
                    env[regs[0]] = setOf("installer")
                }

                instruction.startsWith("new-instance") -> {
                    require(regs.isNotEmpty()) { "UNSUPPORTED_LAYOUT: new-instance without register" }
                    env[regs[0]] = setOf("new:$lineNo")
                }

                instruction.startsWith("const") -> {
                    require(regs.isNotEmpty()) { "UNSUPPORTED_LAYOUT: const without register" }
                    env[regs[0]] = if (CONST_ZERO.containsMatchIn(instruction)) setOf("null") else UNKNOWN
                }

                instruction.startsWith("return-object") -> {
                    val tags = value(regs.firstOrNull() ?: error("UNSUPPORTED_LAYOUT: return-object"))
                    if (modern) {
                        require(tags.all { it == "null" || it == "info" }) {
                            "UNSUPPORTED_LAYOUT: unverified InstallSourceInfo return"
                        }
                    } else if (tags.all { it == "installer" || it == "null" } && "installer" in tags) {
                        sites[lineNo] = regs[0]
                    } else {
                        require(tags == setOf("null")) { "UNSUPPORTED_LAYOUT: unverified installer return" }
                    }
                }

                regs.isNotEmpty() && instruction.startsWith("if-").not() &&
                    listOf("return", "throw", "iput", "sput", "aput", "monitor-", ":", "goto", "check-cast")
                        .none { instruction.startsWith(it) } -> {
                    env[regs[0]] = UNKNOWN
                }
            }

            val successors = mutableListOf<Int>()
            when {
                instruction.startsWith("return") || instruction.startsWith("throw") -> Unit
                instruction.startsWith("goto") -> {
                    val label = instruction.split(WHITESPACE).last()
                    successors += labels[label] ?: error("UNSUPPORTED_LAYOUT: unknown label $label")
                }

                else -> {
                    if (n + 1 < code.size) successors += n + 1
                    if (instruction.startsWith("if-")) {
                        val label = instruction.split(",").last().trim()
                        successors += labels[label] ?: error("UNSUPPORTED_LAYOUT: unknown label $label")
                    }
                }
            }

            for (successor in successors) {
                val previous = states[successor]
                val merged: MutableMap<String, Set<String>> = if (previous == null) {
                    env.toMutableMap()
                } else {
                    val keys = previous.keys + env.keys
                    keys.associateWithTo(mutableMapOf()) { key ->
                        (previous[key] ?: UNKNOWN) + (env[key] ?: UNKNOWN)
                    }
                }
                if (previous != merged) {
                    states[successor] = merged
                    queue.add(successor)
                }
            }
        }

        require(sites.isNotEmpty() && (!modern || sites.size == 1)) {
            "UNSUPPORTED_LAYOUT: expected proven installer return/constructor"
        }
        return sites
    }

    // ==================================================================
    // Patching
    // ==================================================================

    private fun hookBlock(scratch: Int, real: String): String =
        "    move-object/16 v${scratch + 4}, $real\n" +
            "    invoke-static/range {v$scratch .. v${scratch + 4}}, $HOOK\n" +
            "    move-result-object $real\n"

    private fun prologue(base: Int, width: Int, scratch: Int): String = buildString {
        for (i in 0 until width) {
            val suffix = if (i < 2) "-object" else ""
            append("    move$suffix/16 v${base + i}, p$i\n")
        }
        append("    const/16 v$scratch, 0x0\n")
        append("    invoke-static {}, Landroid/os/Binder;->getCallingUid()I\n")
        append("    move-result v${scratch + 1}\n")
        append("    move-object/16 v${scratch + 3}, v${base + 1}\n")
        if (width == 3) {
            append("    move/16 v${scratch + 2}, v${base + 2}\n")
        } else {
            append("    invoke-static/range {v${scratch + 1} .. v${scratch + 1}}, " +
                "Landroid/os/UserHandle;->getUserId(I)I\n")
            append("    move-result v${scratch + 2}\n")
        }
    }

    private fun patchMethod(method: Method): String {
        val normalized = normalize(method)
        val modern = method.header.split(WHITESPACE).last().startsWith("getInstallSourceInfo(")
        val sites = stockSites(normalized.body, normalized.base, normalized.width, modern)

        var body = normalized.body
        for ((lineNo, real) in sites.entries.sortedByDescending { it.key }) {
            val offset = lineStartOffset(body, lineNo)
            body = body.substring(0, offset) + hookBlock(normalized.total, real) + body.substring(offset)
        }

        val directive = REGISTER_DIRECTIVE.find(body)
            ?: error("UNSUPPORTED_LAYOUT: register directive")
        body = body.substring(0, directive.range.first) +
            "    .locals ${normalized.total + 5}\n" +
            body.substring(directive.range.last + 1)

        val prologue = prologue(normalized.base, normalized.width, normalized.total)
        val position = executable(body).first().first
        val offset = lineStartOffset(body, position)
        body = body.substring(0, offset) + prologue + body.substring(offset)

        return ".method ${method.header}\n$body.end method"
    }

    // ==================================================================
    // Canonical form (so sánh bất biến sau khi chuẩn hoá)
    // ==================================================================

    private fun canonical(text: String): String {
        val lines = mutableListOf<String>()
        for (line in text.split("\n")) {
            val value = line.substringBefore('#').trim()
            if (value.isEmpty() || Regex("^\\.(?:line|local|end local|restart local|param|prologue|epilogue)\\b")
                    .containsMatchIn(value)
            ) {
                continue
            }
            lines += if (value.startsWith(".method ") || value.startsWith(".end method")) value
            else "    $value"
        }

        var result = lines.joinToString("\n") + "\n"
        for (method in parse(result).reversed()) {
            val width = if ("Ljava/lang/String;I)" in method.header) 3 else 2
            val normalized = Regex("(?m)^    \\.registers (\\d+)$").replace(method.raw) { m ->
                "    .locals ${m.groupValues[1].toInt() - width}"
            }
            result = result.substring(0, method.start) + normalized + result.substring(method.end)
        }
        return result
    }

    // ==================================================================
    // Helpers
    // ==================================================================

    private fun lineStartOffset(body: String, lineIndex: Int): Int {
        var offset = 0
        repeat(lineIndex) {
            val newline = body.indexOf('\n', offset)
            if (newline < 0) return body.length
            offset = newline + 1
        }
        return offset
    }
}
