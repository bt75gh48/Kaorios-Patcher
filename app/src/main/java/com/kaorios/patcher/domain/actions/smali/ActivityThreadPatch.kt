package com.kaorios.patcher.domain.actions.smali

/**
 * Port của `patch-activitythread-a17.py`.
 *
 * Chèn `KaoriosHook.initActivityThread(Object)` ngay sau phép gán duy nhất
 * `iput-object ... ->mBoundApplication:Landroid/app/ActivityThread$AppBindData;`
 * trong `handleBindApplication(AppBindData)V`.
 *
 * An toàn nằm ở chỗ phải CHỨNG MINH hai toán hạng của phép gán vẫn còn trỏ tới
 * tham số entry (p0 = AppBindData, p1 = this) bằng cách lần theo chuỗi `move-object`.
 * Nếu có bất kỳ ghi đè nào lên alias, hoặc có nhảy ngược vào vùng trước anchor,
 * hàm ném lỗi thay vì chèn hook.
 */
object ActivityThreadPatch {

    private const val HOOK = "Landroid/security/kaorios/KaoriosHook;->initActivityThread(Ljava/lang/Object;)V"

    private val METHOD_RE = Regex(
        "(?m)^\\.method[^\\r\\n]*[ \\t]" +
            "handleBindApplication" +
            "\\(Landroid/app/ActivityThread\\\$AppBindData;\\)V" +
            "[ \\t]*(?:\\r?\\n|$)"
    )
    private val METHOD_END_RE = Regex("(?m)^[ \\t]*\\.end method[ \\t]*(?:\\r?\\n|$)")
    private val ASSIGNMENT_RE = Regex(
        "(?m)^(?<indent>[ \\t]*)" +
            "iput-object[ \\t]+(?<data>[vp]\\d+),[ \\t]*(?<owner>[vp]\\d+),[ \\t]*" +
            "Landroid/app/ActivityThread;->mBoundApplication:" +
            "Landroid/app/ActivityThread\\\$AppBindData;" +
            "[ \\t]*(?:\\r?\\n|$)"
    )
    private val REGISTER_DIRECTIVE = Regex("\\.(registers|locals)\\s+(\\d+)")
    private val STATIC_HEADER = Regex("(?m)^\\.method[^\\n]*\\bstatic\\b")
    private val MOVE_OBJECT = Regex("move-object(?:/from16|/16)?\\s+([vp]\\d+),\\s*([vp]\\d+)")
    private val DESTINATION = Regex("^\\S+\\s+([vp]\\d+)")
    private val HOOK_RE = Regex(Regex.escape(HOOK))
    private val LINE_LABEL = Regex("(?m)^\\s*(:[\\w$]+)\\s*$")
    private val PURE_LABEL = Regex(":[\\w$]+")

    private val CONTROL_PREFIXES = listOf(
        ":", "invoke-", "iput", "sput", "aput", "if-", "goto",
        "return", "throw", "monitor-", "check-cast",
        "packed-switch", "sparse-switch", "fill-array-data"
    )
    private val WIDE_WORDS = listOf("wide", "long", "double")

    /** Trả về (bắt đầu, kết thúc) của method đích. */
    private fun methodSpan(text: String): Pair<Int, Int> {
        val matches = METHOD_RE.findAll(text).toList()
        require(matches.size == 1) {
            "expected exactly one ActivityThread.handleBindApplication(AppBindData); found ${matches.size}"
        }
        val end = METHOD_END_RE.find(text, matches[0].range.last + 1)
            ?: error("unterminated ActivityThread.handleBindApplication")
        return matches[0].range.first to end.range.last + 1
    }

    private fun hookCount(body: String): Int = HOOK_RE.findAll(body).count()

    private data class Anchor(val indent: String, val dataReg: String, val ownerReg: String, val endIndex: Int, val call: String)

    private fun anchor(body: String): Anchor {
        val assignments = ASSIGNMENT_RE.findAll(body).toList()
        require(assignments.size == 1) {
            "expected exactly one mBoundApplication assignment; found ${assignments.size}"
        }
        val assignment = assignments[0]
        require(!STATIC_HEADER.containsMatchIn(body)) { "unsupported static handleBindApplication" }

        val registers = REGISTER_DIRECTIVE.find(body) ?: error("unsupported ActivityThread register layout")
        val count = registers.groupValues[2].toInt()
        val base = if (registers.groupValues[1] == "registers") count - 2 else count
        require(base >= 0) { "unsupported ActivityThread parameter count" }

        fun physical(reg: String): Int = reg.drop(1).toInt() + if (reg.startsWith("p")) base else 0

        val aliases = mutableMapOf(base to 0, base + 1 to 1)
        val prefix = body.substring(0, assignment.range.first)
        var initialMoves = true

        prefix.lineSequence().forEach { line ->
            val instruction = line.trim().substringBefore("#").trim()
            if (instruction.isEmpty() || instruction.startsWith(".")) return@forEach
            val move = MOVE_OBJECT.matchEntire(instruction)
            if (initialMoves && move != null && physical(move.groupValues[2]) in aliases) {
                aliases[physical(move.groupValues[1])] = aliases.getValue(physical(move.groupValues[2]))
                return@forEach
            }
            initialMoves = false
            // Bất kỳ ghi nào lên alias đã chứng minh đều bị từ chối, kể cả word thứ hai của wide.
            if (CONTROL_PREFIXES.any { instruction.startsWith(it) }) return@forEach
            val destination = DESTINATION.find(instruction)
            if (destination != null) {
                val written = physical(destination.groupValues[1])
                val wide = WIDE_WORDS.any { it in instruction.split(Regex("\\s+")).first() }
                require(written !in aliases && !(wide && written + 1 in aliases)) {
                    "unsupported ActivityThread alias overwritten before assignment"
                }
            }
        }

        val dataReg = assignment.groups["data"]!!.value
        val ownerReg = assignment.groups["owner"]!!.value
        require(aliases[physical(dataReg)] == 1 && aliases[physical(ownerReg)] == 0) {
            "unsupported mBoundApplication operands are not proven entry parameter aliases"
        }

        // Nhảy ngược có thể quay lại anchor sau khi alias đã bị đổi.
        val labels = LINE_LABEL.findAll(prefix).map { it.groupValues[1] }.toSet()
        val suffix = body.substring(assignment.range.last + 1)
        suffix.lineSequence().forEach { line ->
            val instruction = line.trim()
            if (instruction.startsWith("if-") || instruction.startsWith("goto")) {
                require(PURE_LABEL.findAll(instruction).none { it.value in labels }) {
                    "unsupported ActivityThread back edge into anchor prefix"
                }
            }
            if (PURE_LABEL.matches(instruction)) {
                require(instruction !in labels) { "unsupported ActivityThread switch/back-edge label" }
            }
        }

        val high = physical(dataReg) > 15
        val call = if (high) "invoke-static/range {$dataReg .. $dataReg}, $HOOK"
        else "invoke-static {$dataReg}, $HOOK"
        return Anchor(assignment.groups["indent"]!!.value, dataReg, ownerReg, assignment.range.last + 1, call)
    }

    fun verify(text: String) {
        val (start, end) = methodSpan(text)
        val body = text.substring(start, end)
        require(hookCount(body) == 1) { "expected exactly one Object initActivityThread hook" }
        val a = anchor(body)
        val following = body.substring(a.endIndex).trimStart()
        require(following.startsWith(a.call)) {
            "Object initActivityThread hook must immediately follow proven assignment"
        }
        val afterCall = following.substring(a.call.length)
        require(afterCall.lineSequence().first().isBlank()) {
            "Object initActivityThread hook must immediately follow proven assignment"
        }
    }

    fun patch(text: String, update: Boolean = false): Pair<String, Boolean> {
        var content = text
        var (start, end) = methodSpan(content)
        var body = content.substring(start, end)
        var count = hookCount(body)
        require(count <= 1) { "unsupported update: multiple Object initActivityThread hooks" }

        if (count == 1) {
            try {
                verify(content)
                return content to false
            } catch (e: IllegalArgumentException) {
                if (!update) throw e
            }
        }

        if (count >= 1 && update) {
            val hookPattern = Regex(
                "(?m)^[ \\t]*invoke-static(?:/range)?[^\\n]*" + Regex.escape(HOOK) + "[ \\t]*(?:\\r?\\n|$)"
            )
            body = hookPattern.replace(body, "")
            content = content.substring(0, start) + body + content.substring(end)
            val span = methodSpan(content)
            start = span.first
            end = span.second
            body = content.substring(start, end)
            count = hookCount(body)
        }

        if (count == 1) {
            verify(content)
            return content to false
        }
        require(count <= 1) { "multiple Object initActivityThread hooks already present" }

        val a = anchor(body)
        val newline = if ("\r\n" in content) "\r\n" else "\n"
        val hook = "${a.indent}${a.call}$newline"
        val patchedBody = body.substring(0, a.endIndex) + hook + body.substring(a.endIndex)
        val patched = content.substring(0, start) + patchedBody + content.substring(end)
        verify(patched)
        return patched to true
    }
}
