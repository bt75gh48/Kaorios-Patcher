package com.kaorios.patcher.editor

/** Tiện ích parse nội dung smali: method list, strings, method-invoke reference, field-access reference. */
object SmaliParse {

    data class MethodEntry(
        val line: Int,
        val signature: String,
        val name: String,
        val modifiers: String,
        val descriptor: String // (params)returnType
    )

    data class StringEntry(
        val line: Int,
        val raw: String,          // nguyên dòng smali
        val value: String          // nội dung string (giữa dấu ")
    )

    data class MethodInvokeRef(
        val ownerType: String,    // Lcom/foo/Bar;
        val name: String,
        val descriptor: String
    )

    data class FieldAccessRef(
        val ownerType: String,
        val name: String,
        val type: String
    )

    private val METHOD_RE = Regex("""^\s*\.method\s+([^\n]+?)\s+([\w$<>\-]+)(\([^)]*\)[^\s]+)\s*$""")
    private val CONST_STRING_RE = Regex("""^\s*const-string(?:/jumbo)?\s+[vp]\d+\s*,\s*"(.*)"\s*$""")
    private val INVOKE_RE = Regex(
        """(?i)^\s*invoke-(?:virtual|direct|static|super|interface)(?:/range)?\s*\{[^}]*\}\s*,\s*(L[^;]+;)->([^\s(]+)(\([^)]*\)[^\s]+)\s*$"""
    )
    private val FIELD_RE = Regex(
        """^\s*[is](?:get|put)(?:-[a-z]+)?\s+[vp\d, ]+\s*(L[^;]+;)->([^:\s]+):([^\s]+)\s*$"""
    )

    fun parseMethods(content: String): List<MethodEntry> {
        val out = mutableListOf<MethodEntry>()
        content.lineSequence().forEachIndexed { idx, line ->
            METHOD_RE.find(line)?.let { m ->
                out.add(
                    MethodEntry(
                        line = idx,
                        signature = line.trim(),
                        modifiers = m.groupValues[1].trim(),
                        name = m.groupValues[2],
                        descriptor = m.groupValues[3]
                    )
                )
            }
        }
        return out
    }

    fun parseStrings(content: String): List<StringEntry> {
        val out = mutableListOf<StringEntry>()
        content.lineSequence().forEachIndexed { idx, line ->
            CONST_STRING_RE.find(line)?.let { m ->
                out.add(StringEntry(idx, line.trim(), m.groupValues[1]))
            }
        }
        return out
    }

    fun parseInvoke(line: String): MethodInvokeRef? =
        INVOKE_RE.find(line)?.let {
            MethodInvokeRef(it.groupValues[1], it.groupValues[2], it.groupValues[3])
        }

    fun parseFieldAccess(line: String): FieldAccessRef? =
        FIELD_RE.find(line)?.let {
            FieldAccessRef(it.groupValues[1], it.groupValues[2], it.groupValues[3])
        }

    /** Trả về relative path smali từ type (ví dụ `Lcom/foo/Bar;` → `com/foo/Bar.smali`). */
    fun typeToSmaliRelPath(type: String): String {
        val internal = if (type.startsWith("L") && type.endsWith(";")) {
            type.substring(1, type.length - 1)
        } else type
        return "$internal.smali"
    }
}
