package com.kaorios.patcher.domain.actions.smali

import java.security.MessageDigest

/**
 * Các primitive dùng chung cho bộ patch Kaorios 2.0.6.2.
 *
 * Port 1-1 từ `kaorios_patcher.py`. Mọi hàm đều fail-closed: layout lạ → ném lỗi,
 * không bao giờ đoán ý.
 */
object SmaliOps {

    /** Literal chuỗi hoặc comment trên một dòng. */
    private val STRING_OR_COMMENT = Regex("\"(?:\\\\.|[^\"\\\\])*\"|#.*")

    private val REGISTER_TOKEN = Regex("""\b([vp]\d+)\b""")

    /** Nhãn duy nhất trong method: base nếu chưa tồn tại, ngược lại base_<hex4>. */
    fun uniqueLabel(base: String, methodBody: String): String {
        var candidate = base
        var suffix = sha256Hex(methodBody).take(4)
        while (Regex("(?m)^\\s*${Regex.escape(candidate)}\\s*$").containsMatchIn(methodBody)) {
            candidate = "${base}_$suffix"
            suffix = sha256Hex(methodBody + suffix).take(4)
        }
        return candidate
    }

    private fun sha256Hex(text: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** Bỏ literal chuỗi và comment khỏi một dòng, giữ phần code. */
    fun stripData(line: String): String = line.replace(STRING_OR_COMMENT, "")

    /**
     * Tương đương `re.split` của Python khi pattern có capture group: trả về danh sách
     * xen kẽ [code, data, code, data, ...]. Phần tử ở chỉ số chẵn là code.
     */
    fun splitCodeAndData(text: String): List<String> {
        val parts = mutableListOf<String>()
        var position = 0
        for (match in STRING_OR_COMMENT.findAll(text)) {
            parts.add(text.substring(position, match.range.first))
            parts.add(match.value)
            position = match.range.last + 1
        }
        parts.add(text.substring(position))
        return parts
    }

    /** Thay thế trên các đoạn code, giữ nguyên byte của string và comment. */
    fun replaceInCode(text: String, transform: (String) -> String): String {
        val parts = splitCodeAndData(text)
        val sb = StringBuilder(text.length)
        parts.forEachIndexed { index, part ->
            sb.append(if (index % 2 == 1) part else transform(part))
        }
        return sb.toString()
    }

    /**
     * Đổi alias `vN` của tham số thành `pN` trước khi thay directive `.registers`
     * bằng `.locals`, để tham số không bị dịch vật lý.
     *
     * @param registers số thanh ghi trong directive GỐC (trước khi bump)
     * @param paramCount số tham số vật lý của method
     */
    fun canonicalizeParamAliases(methodBody: String, registers: Int, paramCount: Int): String {
        val firstParamV = registers - paramCount
        require(firstParamV >= 0 && registers + 1 <= 65535) { "unsupported register allocation" }

        // Dải /range vắt qua ranh giới local/tham số sẽ lệch sau khi bump.
        methodBody.lineSequence().forEach { line ->
            val instruction = stripData(line)
            if ("/range" !in instruction) return@forEach
            Regex("\\{\\s*v(\\d+)\\s*\\.\\.\\s*([vp]\\d+)\\s*}").findAll(instruction).forEach { span ->
                val start = span.groupValues[1].toInt()
                val end = span.groupValues[2]
                val endPhysical = if (end.startsWith("p")) null else end.drop(1).toIntOrNull()
                if (start < firstParamV && (endPhysical == null || endPhysical >= firstParamV)) {
                    error("unsupported register shift: /range crosses local/parameter boundary")
                }
            }
        }

        val normalized = replaceInCode(methodBody) { code ->
            var result = code
            for (n in 0 until paramCount) {
                result = Regex("(?<![\\w/$;>:])v${firstParamV + n}(?![\\w/$;])").replace(result, "p$n")
            }
            result
        }

        verifyRegisterEncoding(normalized, registers + 1, paramCount)
        return normalized
    }

    /**
     * Biến thể dùng `base` (vị trí v-register của tham số đầu) thay vì số thanh ghi.
     * Tương ứng `_canonicalize_parameter_aliases` trong `patch-services-a17.py`.
     */
    fun canonicalizeParamAliasesAtBase(methodBody: String, base: Int, width: Int): String =
        replaceInCode(methodBody) { code ->
            var result = code
            for (parameter in 0 until width) {
                result = Regex("(?<![\\w/$;>:])v${base + parameter}(?![\\w/$;])")
                    .replace(result, "p$parameter")
            }
            result
        }

    /**
     * Từ chối các toán hạng có encoding không hợp lệ sau khi dịch tham số.
     *
     * @param registers số thanh ghi sau khi bump (dùng để tính vị trí vật lý của pN)
     */
    fun verifyRegisterEncoding(methodBody: String, registers: Int, paramCount: Int) {
        methodBody.lineSequence().forEach { line ->
            val instruction = stripData(line).trim()
            if (instruction.isEmpty() || instruction.startsWith(".") || instruction.startsWith(":")) {
                return@forEach
            }
            val opcode = instruction.split(Regex("\\s+")).first()
            var operands = instruction.split(Regex(",\\s*(?:L|\\[)"), 2).first()
            operands = Regex("(?<![\\w]):[\\w$]+").replace(operands, "")
            val names = REGISTER_TOKEN.findAll(operands).map { it.groupValues[1] }.toList()

            val narrow = opcode in NARROW_OPCODES ||
                opcode.startsWith("iget") || opcode.startsWith("iput") ||
                opcode.startsWith("neg-") || opcode.startsWith("not-") ||
                "-to-" in opcode ||
                opcode.endsWith("/2addr") ||
                ((opcode.startsWith("invoke-") || opcode.startsWith("filled-new-array")) && "/range" !in opcode)

            names.forEachIndexed { index, name ->
                val physical = name.drop(1).toInt() + if (name.startsWith("p")) registers - paramCount else 0
                var limit = if (narrow) 15 else 255
                val isInvokeRange =
                    (opcode.startsWith("invoke-") || opcode.startsWith("filled-new-array")) && "/range" in opcode
                val isMove16 = opcode.startsWith("move") && opcode.endsWith("/16")
                val isMoveFrom16Dest = opcode.startsWith("move") && opcode.endsWith("/from16") && index == 1
                if (isInvokeRange || isMove16) {
                    limit = 65535
                } else if (isMoveFrom16Dest) {
                    limit = 65535
                }
                require(physical <= limit) {
                    "unsupported register shift: $opcode operand $name becomes v$physical, encoding limit v$limit"
                }
            }
        }
    }

    /** Opcode chỉ mã hoá được thanh ghi 4-bit. */
    private val NARROW_OPCODES = setOf(
        "move", "move-wide", "move-object", "const/4", "array-length",
        "rsub-int", "add-int/lit16", "and-int/lit16", "or-int/lit16", "xor-int/lit16",
        "new-array", "instance-of", "if-eq", "if-ne", "if-lt", "if-ge", "if-gt", "if-le"
    )
}
