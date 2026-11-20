package com.kaorios.patcher.domain.actions

import com.kaorios.patcher.data.log.LogSink
import java.io.File

/**
 * Low-level smali parser/editor cho Action Scripts.
 *
 * Cung cấp:
 * - Tìm file .smali từ class descriptor (`Landroid/foo/Bar;` → `classes[N]/android/foo/Bar.smali`).
 * - Trích method body theo signature (bao gồm dấu cách trước tên method để match chính xác).
 * - Check từ cấm (banned keywords trong các dòng invoke).
 * - Ghi lại file sau khi thay method body.
 *
 * Thiết kế dùng `String` list để edit bằng line-level, đơn giản và đủ cho smali format.
 */
object SmaliEngine {

    private val BUILD_NULL_FIELDS = listOf(
        "BRAND", "BRAND_FOR_ATTESTATION", "DEVICE", "DEVICE_FOR_ATTESTATION",
        "FINGERPRINT", "HARDWARE", "ID", "MANUFACTURER", "MANUFACTURER_FOR_ATTESTATION",
        "MODEL", "MODEL_FOR_ATTESTATION", "PRODUCT", "PRODUCT_FOR_ATTESTATION",
        "TAGS", "TYPE", "USER"
    )
    private val BUILD_VERSION_FIELDS = listOf(
        "RELEASE", "RELEASE_OR_CODENAME", "RELEASE_OR_PREVIEW_DISPLAY",
        "SECURITY_PATCH", "DEVICE_INITIAL_SDK_INT"
    )

    /** Port of the 2.0.6.2 Build field patch. SDK_INT is intentionally excluded. */
    fun patchBuildFields(content: String): String {
        require(" TIME:J" in content) { "Build.smali: TIME:J not found" }
        var result = content
        BUILD_NULL_FIELDS.forEach { field ->
            val pattern = Regex("(?m)^(\\.field public static[^\\n]*?)final([^\\n]*? $field:Ljava/lang/String;)(?:[ \\t]*=[^\\n]*)?$")
            val absent = !Regex("(?m)^\\.field public static[^\\n]* $field:Ljava/lang/String;").containsMatchIn(result)
            if (absent && field.endsWith("_FOR_ATTESTATION")) return@forEach
            require(!absent) { "Build.smali: $field not found" }
            result = pattern.replace(result) { "${it.groupValues[1]}${it.groupValues[2]} = null" }
            result = Regex("(?m)^(\\.field public static[^\\n]* $field:Ljava/lang/String;)[ \\t]*=[^\\n]*$")
                .replace(result, "$1 = null")
        }
        result = Regex("(?m)^(\\.field public static[^\\n]*?)final([^\\n]*? TIME:J.*)$")
            .replace(result, "$1$2")
        return result
    }

    /** Port of the 2.0.6.2 Build$VERSION field patch. */
    fun patchBuildVersionFields(content: String): String {
        var result = content
        BUILD_VERSION_FIELDS.forEach { field ->
            val pattern = Regex("(?m)^(\\.field public static[^\\n]*?)final([^\\n]*? $field:[^\\s]+.*)$")
            require(Regex("(?m)^\\.field public static[^\\n]* $field:[^\\s]+").containsMatchIn(result)) {
                "Build\$VERSION.smali: $field not found"
            }
            result = pattern.replace(result, "$1$2")
        }
        return result
    }

    fun verifyBuildFields(content: String) {
        BUILD_NULL_FIELDS.forEach { field ->
            val match = Regex("(?m)^\\.field public static[^\\n]* $field:Ljava/lang/String;.*$").find(content)
            if (match == null && field.endsWith("_FOR_ATTESTATION")) return@forEach
            require(match != null && " final" !in match.value && "= null" in match.value) {
                "Build.smali: invalid post-patch field $field"
            }
        }
        val time = Regex("(?m)^\\.field public static[^\\n]* TIME:J.*$").find(content)
        require(time != null && " final" !in time.value) { "Build.smali: TIME:J remains final" }
        require(Regex("(?m)^\\.field public static[^\\n]* SDK_INT:").find(content)?.value?.contains(" final") != false) {
            "Build.smali: SDK_INT must remain final"
        }
    }

    fun verifyBuildVersionFields(content: String) {
        BUILD_VERSION_FIELDS.forEach { field ->
            val line = Regex("(?m)^\\.field public static[^\\n]* $field:[^\\s]+.*$").find(content)
            require(line != null && " final" !in line.value) { "Build\$VERSION.smali: invalid $field" }
        }
    }

    val BANNED_KEYWORDS = listOf(
        "kaorios", "crdroid", "neotric", "evolution",
        "setprop", "setprops", "hook", "android/util", "axion", "danda", "moonos", "elite"
    )

    /**
     * Vùng method trong file: khoảng `[start, end]` line (inclusive), kèm signature.
     */
    data class MethodSlice(
        val file: File,
        val startLine: Int,     // dòng `.method ...`
        val endLine: Int,       // dòng `.end method`
        val lines: List<String> // các dòng nguyên bản trong phạm vi (inclusive)
    ) {
        val header: String get() = lines.first()
    }

    /**
     * Body method mutable — để các op patch chỉnh sửa.
     * `body` chứa các dòng GIỮA `.method` và `.end method` (exclusive, không bao gồm 2 dòng đó).
     */
    class MutableMethod(
        val header: String,
        val body: MutableList<String>
    ) {
        fun render(): String = buildString {
            append(header); append('\n')
            body.forEach { append(it); append('\n') }
            append(".end method")
        }
    }

    /**
     * Duyệt tất cả root `*_dex_decomp/` và tìm file .smali tương ứng với class descriptor.
     * Trả về file đầu tiên khớp trong `classes[N]/<internalPath>.smali`.
     */
    fun findFileForClass(roots: List<File>, classDescriptor: String): File? {
        val internal = toInternalPath(classDescriptor) ?: return null
        val rel = "$internal.smali"
        for (root in roots) {
            val classesDirs = root.listFiles()
                ?.filter { it.isDirectory && it.name.startsWith("classes", ignoreCase = true) }
                ?: continue
            for (cd in classesDirs) {
                val cand = File(cd, rel)
                if (cand.isFile) return cand
            }
        }
        return null
    }

    /**
     * Tất cả file khớp class descriptor trên mọi root — dùng để phát hiện layout mơ hồ
     * (cùng một class nằm ở nhiều `classesN`), tương ứng guard duplicate của `process_files`.
     */
    fun findAllForClass(roots: List<File>, classDescriptor: String): List<File> {
        val internal = toInternalPath(classDescriptor) ?: return emptyList()
        val rel = "$internal.smali"
        val out = mutableListOf<File>()
        for (root in roots) {
            val classesDirs = root.listFiles()
                ?.filter { it.isDirectory && it.name.startsWith("classes", ignoreCase = true) }
                ?: continue
            for (cd in classesDirs.sortedBy { it.name }) {
                val cand = File(cd, rel)
                if (cand.isFile) out += cand
            }
        }
        return out
    }

    /** `Landroid/app/Activity;` → `android/app/Activity`. Null nếu format sai. */
    fun toInternalPath(desc: String): String? {
        if (!desc.startsWith("L") || !desc.endsWith(";")) return null
        return desc.substring(1, desc.length - 1)
    }

    /**
     * Extract method từ file. Match theo " <methodSignature>" (có dấu cách trước).
     * Ví dụ sig = `hasSystemFeature(Ljava/lang/String;I)Z` → match line chứa ` hasSystemFeature(Ljava/lang/String;I)Z`.
     */
    fun extractMethod(file: File, methodSignature: String): MethodSlice? {
        val text = runCatching { file.readText() }.getOrElse {
            LogSink.e("SmaliEngine", "read fail ${file.absolutePath}", it); return null
        }
        val lines = text.split('\n')
        val sigPattern = " $methodSignature"
        var start = -1
        var end = -1
        for ((i, line) in lines.withIndex()) {
            if (start < 0) {
                val trim = line.trimStart()
                if (trim.startsWith(".method") && line.contains(sigPattern)) {
                    start = i
                }
            } else {
                if (line.trim() == ".end method") {
                    end = i; break
                }
            }
        }
        if (start < 0 || end < 0) return null
        return MethodSlice(file, start, end, lines.subList(start, end + 1))
    }

    /**
     * Tìm khối `.method` … `.end method` có ít nhất một dòng chứa [lineSubstring].
     * Dùng khi ROM đổi tên entry (vd `main([Ljava/lang/String;)V` thay `run()V`) nhưng vẫn gọi neo giống nhau.
     */
    fun extractMethodContainingLine(file: File, lineSubstring: String): MethodSlice? {
        val text = runCatching { file.readText() }.getOrElse {
            LogSink.e("SmaliEngine", "read fail ${file.absolutePath}", it); return null
        }
        val lines = text.split('\n')
        val anchorIdx = lines.indexOfFirst { it.contains(lineSubstring) }
        if (anchorIdx < 0) return null
        var start = anchorIdx
        while (start >= 0 && !lines[start].trimStart().startsWith(".method")) start--
        if (start < 0) return null
        var end = anchorIdx
        while (end < lines.size && lines[end].trim() != ".end method") end++
        if (end >= lines.size) return null
        return MethodSlice(file, start, end, lines.subList(start, end + 1))
    }

    fun toMutable(slice: MethodSlice): MutableMethod {
        val body = slice.lines.subList(1, slice.lines.size - 1).toMutableList()
        return MutableMethod(slice.lines.first(), body)
    }

    /** Trả lại string của method hợp nhất. */
    fun renderMethod(m: MutableMethod): String = m.render()

    /**
     * Ghi trở lại file với method body mới. Giữ nguyên các phần trước/sau method.
     */
    fun writeMethodBack(slice: MethodSlice, newBody: MutableMethod) {
        val original = slice.file.readText()
        val lines = original.split('\n').toMutableList()
        // Remove [startLine..endLine]
        val before = lines.subList(0, slice.startLine).toList()
        val after = if (slice.endLine + 1 < lines.size) lines.subList(slice.endLine + 1, lines.size).toList() else emptyList()
        val newLines = newBody.render().split('\n')
        val combined = buildList {
            addAll(before); addAll(newLines); addAll(after)
        }
        slice.file.writeText(combined.joinToString("\n"))
    }

    /** Check từ cấm trong các dòng invoke-*. Trả về từ cấm đầu tiên gặp hoặc null. */
    fun findBannedKeyword(method: MutableMethod): String? {
        for (line in method.body) {
            val t = line.trimStart()
            if (!t.startsWith("invoke-")) continue
            val lower = line.lowercase()
            for (b in BANNED_KEYWORDS) {
                if (lower.contains(b)) return b
            }
        }
        return null
    }

    // --- Patch primitives ---

    /** Chèn khối sau dòng .registers X (tìm line đầu tiên khớp). */
    fun insertAfterRegisters(method: MutableMethod, block: String) {
        val idx = method.body.indexOfFirst { it.trimStart().startsWith(".registers ") }
        if (idx < 0) error("Không tìm thấy `.registers` trong method ${methodName(method)}")
        require(method.body.count { it.trim() == "#Start KaoriosHook" } == 0) {
            "Duplicate KaoriosHook block in ${methodName(method)}"
        }
        method.body.addAll(idx + 1, wrapHookBlock(block))
    }

    /** Reject duplicate hooks before any new hook block is inserted. */
    fun requireSingleExecutableHook(method: MutableMethod, hook: String) {
        val escaped = Regex.escape(hook)
        val count = method.body.count { Regex("^\\s*${escaped}\\s*(?:#.*)?$").matches(it) }
        require(count <= 1) { "Duplicate executable hook: $hook" }
    }

    /** Verify the two preceding branches are conditional-zero guards. */
    fun requireIfEqzPredecessors(method: MutableMethod, anchor: String) {
        val idx = method.body.indexOfFirst { it.contains(anchor) }
        require(idx >= 2) { "Missing branch-order anchor: $anchor" }
        val branches = method.body.subList(0, idx).asReversed()
            .filter { it.trim().startsWith("if-") }
            .take(2)
        require(branches.size == 2 && branches.all { it.trim().startsWith("if-eqz") }) {
            "Unsafe branch order before $anchor"
        }
    }

    /** Chèn khối sau dòng `.end annotation`. */
    fun insertAfterEndAnnotation(method: MutableMethod, block: String) {
        val idx = method.body.indexOfFirst { it.trim() == ".end annotation" }
        if (idx < 0) error("Không tìm thấy `.end annotation` trong method ${methodName(method)}")
        method.body.addAll(idx + 1, wrapHookBlock(block))
    }

    /** Chèn khối sau dòng chứa [containsText]. */
    fun insertAfterLineContaining(method: MutableMethod, containsText: String, block: String) {
        val idx = method.body.indexOfFirst { it.contains(containsText) }
        if (idx < 0) error("Không tìm dòng chứa `$containsText` trong ${methodName(method)}")
        method.body.addAll(idx + 1, wrapHookBlock(block))
    }

    /**
     * Chèn khối hide dev check TRƯỚC dòng chứa [beforeAnchorLine] trong method NameValueCache.
     * Tự động quét tất cả thanh ghi đang dùng TRƯỚC vị trí chèn trong phạm vi v0-v15
     * (smali backward-compat: apiLevel <= 29 chỉ cho phép v0-v15) để tìm 4 thanh ghi trống.
     */
    fun insertHideDevBeforeAnchor(method: MutableMethod, beforeAnchorLine: String) {
        val idx = method.body.indexOfFirst { it.contains(beforeAnchorLine) }
        if (idx < 0) error("Không tìm dòng chứa `$beforeAnchorLine` trong ${methodName(method)}")

        // Quét các dòng TRƯỚC vị trí chèn để tìm thanh ghi đã dùng
        val usedRegs = mutableSetOf<Int>()
        for (i in 0 until idx) {
            usedRegs.addAll(extractUsedRegisters(method.body[i]))
        }

        // Tìm 4 thanh ghi trống liên tiếp trong v0-v15
        val freeRegs = findConsecutiveFreeRegisters(usedRegs.toList().sorted(), 4, 15)
            ?: error("Không đủ thanh ghi trống (cần 4, giới hạn v0-v15) trong ${methodName(method)}")

        val v1 = freeRegs[0]
        val v2 = freeRegs[1]
        val v3 = freeRegs[2]
        val v4 = freeRegs[3]

        val block = listOf(
            "    move-object/from16 v$v1, p1",
            "",
            "    invoke-virtual {v$v1}, Landroid/content/ContentResolver;->getPackageName()Ljava/lang/String;",
            "",
            "    move-result-object v0",
            "    move-object/from16 v$v2, v0",
            "",
            "    invoke-static {v$v1, v$v2, p2}, Landroid/security/kaorios/KaoriosHook;->shouldHideDevStatus(Landroid/content/ContentResolver;Ljava/lang/String;Ljava/lang/String;)Z",
            "",
            "    move-result v0",
            "    move/from16 v$v4, v0",
            "",
            "    if-eqz v$v4, :cond_kaorios",
            "",
            "    const-string v$v3, \"0\"",
            "",
            "    return-object v$v3",
            "",
            "    :cond_kaorios"
        )

        method.body.addAll(idx, block.map { "    $it" })
    }

    /**
     * Tìm [count] thanh ghi trống liên tiếp từ danh sách đã sắp xếp.
     * @param sortedUsed Danh sách số thanh ghi đã dùng (đã sort)
     * @param count Số lượng thanh ghi cần tìm
     * @param maxReg Giới hạn max của thanh ghi (default v15 cho smali backward-compat apiLevel <= 29)
     */
    private fun findConsecutiveFreeRegisters(sortedUsed: List<Int>, count: Int, maxReg: Int = 15): List<Int>? {
        val available = (0..maxReg).filter { it !in sortedUsed }
        if (available.size < count) return null
        return available.take(count)
    }

    /**
     * Trích tất cả số thanh ghi từ một dòng smali.
     * Nhận diện: move*, invoke-*, move-result*, if-*, const*, return*, aput*,aget*,...
     * Chỉ lấy các thanh ghi dạng `vN`, không lấy `pN` (param) hay `wN`/`rN` (wide).
     */
    private fun extractUsedRegisters(line: String): Set<Int> {
        val result = mutableSetOf<Int>()
        val t = line.trim()
        if (t.isEmpty() || t.startsWith("#") || t.startsWith(":")) return result

        // Pattern: các thanh ghi xuất hiện sau opcode trong dòng
        // Bỏ qua comment trailing
        val content = t.substringBefore("#").trim()
        if (content.isEmpty()) return result

        // Invoke register list: {v0, v1, p2, ...} hoặc {p0, p1}
        val invokeMatch = Regex("""\{\s*([^}]*?)\s*\}""").find(content)
        if (invokeMatch != null && content.startsWith("invoke-")) {
            for (reg in invokeMatch.groupValues[1].split(",")) {
                val r = reg.trim()
                if (r.startsWith("v") && !r.startsWith("wide")) {
                    val numPart = r.substringAfter("v").trim()
                    numPart.toIntOrNull()?.let { result.add(it) }
                }
            }
            return result
        }

        // filled-new-array/range {p2 .. p2}
        val rangeMatch = Regex("""\{\s*(\w+)\s*\.\.\s*(\w+)\s*\}""").find(content)
        if (rangeMatch != null) {
            val start = rangeMatch.groupValues[1].trim()
            if (start.startsWith("v")) {
                start.substringAfter("v").toIntOrNull()?.let { result.add(it) }
            }
            return result
        }

        // Standard register operands: op vD, vS[, vT] hoặc op vX, literal
        val operands = Regex(""",\s*""").split(content)
        for (op in operands) {
            val parts = op.trim().split(Regex("""\s+"""))
            for (p in parts) {
                val pp = p.trim()
                // Chỉ lấy vN thuần túy, không phải pN, không phải wide vN
                if (pp.startsWith("v") && !pp.startsWith("wide")) {
                    val numPart = pp.substringAfter("v")
                    numPart.toIntOrNull()?.let { result.add(it) }
                }
            }
        }

        return result
    }

    /** Tăng `.registers X → X+1` và trả về register mới được giải phóng (dưới dạng "vN"). */
    fun incrementRegistersAndReturnFree(method: MutableMethod): String {
        val idx = method.body.indexOfFirst { it.trimStart().startsWith(".registers ") }
        if (idx < 0) error("Không tìm thấy `.registers` trong method ${methodName(method)}")
        val m = Regex("""^(\s*)\.registers\s+(\d+)\s*$""").matchEntire(method.body[idx])
            ?: error("`.registers` line không parse được: '${method.body[idx]}'")
        val indent = m.groupValues[1]
        val oldN = m.groupValues[2].toInt()
        val newN = oldN + 1
        method.body[idx] = "$indent.registers $newN"
        val paramCount = paramRegisterCount(method.header, methodSignature(method.header))
        // Free register = register đầu tiên của params (sau khi +1 nó lùi về làm local).
        val freeIdx = oldN - paramCount
        return "v$freeIdx"
    }

    /** Tìm dòng return-object CUỐI CÙNG trước `.end method`, trả register (vX/pX). */
    fun findLastReturnObjectRegister(method: MutableMethod): String? {
        for (i in method.body.indices.reversed()) {
            val m = Regex("""^\s*return-object\s+([vp]\d+)\s*$""").matchEntire(method.body[i])
            if (m != null) return m.groupValues[1]
        }
        return null
    }

    /** Chèn block ngay trước dòng return-object cuối cùng. */
    fun insertBeforeLastReturnObject(method: MutableMethod, block: String) {
        val idx = method.body.indexOfLast {
            Regex("""^\s*return-object\s+[vp]\d+\s*$""").matches(it)
        }
        if (idx < 0) error("Không tìm thấy return-object cuối trong ${methodName(method)}")
        method.body.addAll(idx, wrapHookBlock(block) + listOf(""))
    }

    /** Chèn block trước dòng đầu tiên chứa [containsText]. */
    fun insertBeforeLineContaining(method: MutableMethod, containsText: String, block: String) {
        val idx = method.body.indexOfFirst { it.contains(containsText) }
        if (idx < 0) error("Không tìm dòng chứa `$containsText` trong ${methodName(method)}")
        method.body.addAll(idx, wrapHookBlock(block) + listOf(""))
    }

    /**
     * Sau dòng đầu tiên chứa [anchorContains], tìm `move-result*` kế tiếp (bỏ qua dòng trống/comment),
     * tăng `.registers` thêm 1 → trả register mới. Sau đó chèn block dựng từ
     * [blockBuilder]`(movedReg, newReg)` ngay sau dòng `move-result` đó.
     *
     * Dùng cho `WindowManagerService.captureDisplay` — sau `notAllowCaptureDisplay()` → `move-result vY`
     * cần chèn hook isSecureFlag override vY = 0 khi cần.
     * Trả `false` (no-op) nếu không tìm anchor / move-result — dùng khi ROM không có block đó.
     */
    fun tryInsertAfterMoveResultFollowingLine(
        method: MutableMethod,
        anchorContains: String,
        blockBuilder: (movedReg: String, newReg: String) -> String
    ): Boolean {
        val anchorIdx = method.body.indexOfFirst { it.contains(anchorContains) }
        if (anchorIdx < 0) {
            LogSink.detail(
                "SmaliEngine",
                "skip patch · ${methodName(method)} — không có `$anchorContains`"
            )
            return false
        }

        var moveIdx = -1
        var i = anchorIdx + 1
        while (i < method.body.size) {
            val t = method.body[i].trim()
            if (t.isEmpty() || t.startsWith("#") || t.startsWith(".line")) { i++; continue }
            if (t.startsWith("move-result")) { moveIdx = i; break }
            LogSink.detail(
                "SmaliEngine",
                "skip patch · ${methodName(method)} — không có move-result sau anchor"
            )
            return false
        }
        if (moveIdx < 0) {
            LogSink.detail(
                "SmaliEngine",
                "skip patch · ${methodName(method)} — không có move-result sau anchor"
            )
            return false
        }

        val mrMatch = Regex("""^\s*move-result(?:-object|-wide)?\s+([vp]\d+)\s*$""")
            .matchEntire(method.body[moveIdx])
        if (mrMatch == null) {
            LogSink.detail(
                "SmaliEngine",
                "skip patch · ${methodName(method)} — không parse move-result: '${method.body[moveIdx].trim()}'"
            )
            return false
        }
        val movedReg = mrMatch.groupValues[1]

        val newReg = incrementRegistersAndReturnFree(method)
        val block = blockBuilder(movedReg, newReg)
        method.body.addAll(moveIdx + 1, wrapHookBlock(block))
        return true
    }

    fun insertAfterMoveResultFollowingLine(
        method: MutableMethod,
        anchorContains: String,
        blockBuilder: (movedReg: String, newReg: String) -> String
    ) {
        if (!tryInsertAfterMoveResultFollowingLine(method, anchorContains, blockBuilder)) {
            error("Không vá được move-result sau `$anchorContains` trong ${methodName(method)}")
        }
    }

    /**
     * Xoá tất cả dòng TRONG khoảng [afterContaining, beforeContaining]
     * (exclusive 2 đầu). Nếu không có gì giữa → no-op (xác nhận thành công).
     *
     * @param afterContaining substring của line neo đầu (sau line này bắt đầu xoá).
     * @param beforeContaining substring của line neo cuối (trước line này kết thúc xoá).
     * @param findLastAnchor nếu true, anchor-after là line CUỐI khớp thay vì đầu.
     */
    fun deleteLinesBetween(
        method: MutableMethod,
        afterContaining: String?,
        beforeContaining: String,
        fallbackAfter: String? = null,
        findLastBefore: Boolean = false
    ) {
        val startIdx: Int = if (afterContaining != null) {
            val idx = method.body.indexOfFirst { it.contains(afterContaining) }
            if (idx >= 0) idx else fallbackAfter?.let { fb ->
                method.body.indexOfFirst { it.contains(fb) }
            } ?: error("Không tìm anchor `$afterContaining` (fallback=$fallbackAfter) trong ${methodName(method)}")
        } else {
            // Bắt đầu xoá ngay sau `.registers` (default)
            method.body.indexOfFirst { it.trimStart().startsWith(".registers ") }
        }
        if (startIdx < 0) error("Không tìm anchor đầu trong ${methodName(method)}")

        val endIdx = if (findLastBefore) method.body.indexOfLast { it.contains(beforeContaining) }
        else method.body.drop(startIdx + 1).indexOfFirst { it.contains(beforeContaining) }
            .let { if (it >= 0) it + startIdx + 1 else -1 }
        if (endIdx < 0) error("Không tìm anchor cuối `$beforeContaining` trong ${methodName(method)}")
        if (endIdx <= startIdx + 1) return // không có gì để xoá
        // Xoá [startIdx+1, endIdx-1]
        val toRemove = endIdx - startIdx - 1
        repeat(toRemove) { method.body.removeAt(startIdx + 1) }
    }

    /**
     * Anchor chèn hook cho `engineGetCertificateChain`: sau `aput-object value, array, idx` và trước
     * `return-object array` (cùng register mảng chứng chỉ).
     *
     * **Kiểu AOSP (chặt):** `const/4 vX, 0x0` rồi trong vài dòng `aput-object …, …, vX` và `return-object …`.
     *
     * **ROM tùy biến (Pixel / props):** block dài trước `getKeyMetadata`; `aput-object` cuối có thể dùng
     * chỉ số khác `const/4 … 0x0` sát cạnh. Cặp đuôi hợp lệ: sau `aput-object vVal, vArr, vIdx` chỉ có dòng lót
     * (trống, `.line`, `:label`, `nop`…) rồi `return-object vArr` — không có invoke/move giữa hai
     * (tránh trúng `return-object` nhánh sớm hoặc thân vòng lặp).
     */
    fun findEngineGetCertificateChainAputReturn(
        body: List<String>
    ): Triple<Int, String, String>? {
        val tight = findTightConst4AputReturn(body)
        if (tight != null) {
            val (aputIdx, retIdx, arr, retReg) = tight
            return Triple(aputIdx, arr, retReg)
        }
        val loose = findLooseAputReturnSameArray(body)
        if (loose != null) {
            val (aputIdx, retIdx, arr, retReg) = loose
            return Triple(aputIdx, arr, retReg)
        }
        val vendor = findVendorHookBetweenAputAndReturn(body)
        if (vendor != null) {
            val (aputIdx, arr, retReg) = vendor
            return Triple(aputIdx, arr, retReg)
        }
        return null
    }

    /** const/4 vC,0x0 → aput (≤3 dòng) → return-object (≤3 dòng); C = chỉ số aput thứ 3. */
    private fun findTightConst4AputReturn(
        body: List<String>
    ): Quadruple<Int, Int, String, String>? {
        val constRe = Regex("""^\s*const/4\s+([vp]\d+),\s*0x0\s*$""")
        val aputRe = Regex("""^\s*aput-object\s+([vp]\d+),\s*([vp]\d+),\s*([vp]\d+)\s*$""")
        val returnRe = Regex("""^\s*return-object\s+([vp]\d+)\s*$""")
        var i = 0
        while (i < body.size - 2) {
            if (constRe.matchEntire(body[i]) != null) {
                val j = (i + 1..minOf(i + 3, body.size - 1)).firstOrNull { aputRe.matches(body[it]) }
                if (j != null) {
                    val b = aputRe.matchEntire(body[j])!!
                    val k = (j + 1..minOf(j + 3, body.size - 1)).firstOrNull { returnRe.matches(body[it]) }
                    if (k != null) {
                        val arr = b.groupValues[2]
                        val retReg = returnRe.matchEntire(body[k])!!.groupValues[1]
                        return Quadruple(j, k, arr, retReg)
                    }
                }
            }
            i++
        }
        return null
    }

    /**
     * `aput-object vVal, vArr, vIdx` rồi tới `return-object vArr` với **chỉ** dòng lót (trống, `.line`, label…)
     * giữa hai — khớp ROM Pixel (không có `const/4 … 0x0` sát `aput-object` cuối).
     */
    private fun findLooseAputReturnSameArray(
        body: List<String>
    ): Quadruple<Int, Int, String, String>? {
        val aputRe = Regex("""^\s*aput-object\s+([vp]\d+),\s*([vp]\d+),\s*([vp]\d+)\s*$""")
        val returnRe = Regex("""^\s*return-object\s+([vp]\d+)\s*$""")
        var best: Quadruple<Int, Int, String, String>? = null
        for (i in body.indices) {
            val am = aputRe.matchEntire(body[i]) ?: continue
            val arr = am.groupValues[2]
            val end = scanForwardNoiseOnlyThenReturn(body, i + 1, arr, aputRe, returnRe) ?: continue
            val retReg = returnRe.matchEntire(body[end])!!.groupValues[1]
            val q = Quadruple(i, end, arr, retReg)
            if (best == null || i > best.first) best = q
        }
        return best
    }

    /**
     * ROM có hook xen giữa (vd `invoke-static …KaoriKeyboxHooks…` + `move-result-object`) trước `return-object` cùng mảng.
     */
    private fun findVendorHookBetweenAputAndReturn(
        body: List<String>
    ): Triple<Int, String, String>? {
        val aputRe = Regex("""^\s*aput-object\s+([vp]\d+),\s*([vp]\d+),\s*([vp]\d+)\s*$""")
        val returnRe = Regex("""^\s*return-object\s+([vp]\d+)\s*$""")
        var best: Triple<Int, String, String>? = null
        for (i in body.indices) {
            val am = aputRe.matchEntire(body[i]) ?: continue
            val arr = am.groupValues[2]
            val retIdx = scanForwardInvokeThenReturnSameArray(body, i + 1, arr, returnRe) ?: continue
            val retReg = returnRe.matchEntire(body[retIdx])!!.groupValues[1]
            val t = Triple(i, arr, retReg)
            if (best == null || i > best.first) best = t
        }
        return best
    }

    /**
     * Từ dòng [start]: bỏ qua lót + khối `invoke-*` (kèm `move-result*` ngay sau nếu có) cho tới
     * `return-object` với register trùng [arrayReg].
     */
    private fun scanForwardInvokeThenReturnSameArray(
        body: List<String>,
        start: Int,
        arrayReg: String,
        returnRe: Regex
    ): Int? {
        var j = start
        while (j < body.size) {
            val raw = body[j]
            val t = raw.trim()
            if (isSmaliGapNoiseOnly(t)) {
                j++
                continue
            }
            val rm = returnRe.matchEntire(raw)
            if (rm != null) {
                return if (rm.groupValues[1] == arrayReg) j else null
            }
            if (t.startsWith("invoke-")) {
                j++
                while (j < body.size && isSmaliGapNoiseOnly(body[j].trim())) j++
                if (j < body.size) {
                    val tr = body[j].trim()
                    if (tr.startsWith("move-result")) {
                        j++
                        continue
                    }
                }
                continue
            }
            return null
        }
        return null
    }

    /**
     * Giữa aput và return chỉ được có dòng “lót”; gặp lệnh thật (invoke, move, …) → không phải cặp đuôi.
     * Tránh ghép nhầm với `return-object v0` ở nhánh sớm trong method.
     */
    private fun scanForwardNoiseOnlyThenReturn(
        body: List<String>,
        start: Int,
        arrayReg: String,
        aputRe: Regex,
        returnRe: Regex
    ): Int? {
        var j = start
        while (j < body.size) {
            val raw = body[j]
            val t = raw.trim()
            if (isSmaliGapNoiseOnly(t)) {
                j++
                continue
            }
            if (aputRe.matchEntire(raw) != null) return null
            val rm = returnRe.matchEntire(raw)
            if (rm != null) {
                return if (rm.groupValues[1] == arrayReg) j else null
            }
            return null
        }
        return null
    }

    private fun isSmaliGapNoiseOnly(trimmed: String): Boolean {
        if (trimmed.isEmpty()) return true
        if (trimmed.startsWith(".line")) return true
        if (trimmed == "nop") return true
        if (trimmed.startsWith(".prologue") || trimmed.startsWith(".epilogue")) return true
        return isSmaliLabelLine(trimmed)
    }

    /**
     * Dùng khi xóa hook: giữa aput và return cho phép thêm khối `#Start KaoriosHook` … `#End KaoriosHook`.
     */
    private fun findCertificateChainAputReturnForRemoval(
        body: List<String>
    ): Pair<Int, Int>? {
        val tight = findTightConst4AputReturn(body)
        if (tight != null) return tight.first to tight.second
        val aputRe = Regex("""^\s*aput-object\s+([vp]\d+),\s*([vp]\d+),\s*([vp]\d+)\s*$""")
        val returnRe = Regex("""^\s*return-object\s+([vp]\d+)\s*$""")
        for (i in body.indices.reversed()) {
            val am = aputRe.matchEntire(body[i]) ?: continue
            val arr = am.groupValues[2]
            val end = scanForwardAllowingKaoriosHookBlock(body, i + 1, arr, aputRe, returnRe)
                ?: scanForwardInvokeThenReturnSameArray(body, i + 1, arr, returnRe)
                ?: continue
            return i to end
        }
        return null
    }

    private fun scanForwardAllowingKaoriosHookBlock(
        body: List<String>,
        start: Int,
        arrayReg: String,
        aputRe: Regex,
        returnRe: Regex
    ): Int? {
        var j = start
        while (j < body.size) {
            val raw = body[j]
            val t = raw.trim()
            if (isSmaliGapNoiseOnly(t) || t.startsWith("#") && !t.contains("KaoriosHook")) {
                j++
                continue
            }
            if (t.contains("#Start KaoriosHook")) {
                while (j < body.size && !body[j].trim().contains("#End KaoriosHook")) j++
                if (j < body.size) j++
                continue
            }
            if (aputRe.matchEntire(raw) != null) return null
            val rm = returnRe.matchEntire(raw)
            if (rm != null) {
                return if (rm.groupValues[1] == arrayReg) j else null
            }
            return null
        }
        return null
    }

    private fun isSmaliLabelLine(trimmed: String): Boolean {
        if (trimmed.isEmpty() || trimmed[0] != ':') return false
        // :cond_0, :goto_7e, :catchall_1
        return Regex("""^:[A-Za-z_$][A-Za-z0-9_$]*$""").matches(trimmed)
    }

    private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    /** Tìm 3 dòng liên tiếp const/4 xC, 0x0 / aput-object / return-object; hoặc cặp đuôi ROM tùy biến. */
    fun insertHookAfterAputObjectSequence(
        method: MutableMethod,
        hookTemplate: (xB: String, xD: String) -> String
    ) {
        val anchor = findEngineGetCertificateChainAputReturn(method.body)
            ?: error(
                "Không tìm anchor aput-object → return-object cho certificate chain trong ${methodName(method)} " +
                    "(const/4 chặt, cặp đuôi chỉ-lót, hoặc aput + invoke/move-result + return cùng mảng)."
            )
        val (aputIdx, xB, xD) = anchor
        val hookText = hookTemplate(xB, xD)
        method.body.addAll(aputIdx + 1, wrapHookBlock(hookText))
    }

    /**
     * Xóa block hook Kaorios giữa `aput-object` chứng chỉ và `return-object`.
     * Thứ tự: sequence chặt const/4; nếu không (đã chèn hook / ROM lỏng) thì tìm từ cuối file với
     * khoảng giữa cho phép khối `#Start KaoriosHook` … `#End KaoriosHook`.
     */
    fun removeHookBetweenCertificateChainAputAndReturn(method: MutableMethod) {
        val pair = findCertificateChainAputReturnForRemoval(method.body) ?: return
        val (aIdx, rIdx) = pair
        val toRemove = rIdx - aIdx - 1
        if (toRemove > 0) repeat(toRemove) { method.body.removeAt(aIdx + 1) }
    }

    /**
     * Thay thế toàn bộ body method bằng code gọi hasSystemFeature 2 tham số.
     * Dùng khi xóa hook KaoriosToolbox 1.0.9.
     */
    fun replaceBodyWithHasSystemFeatureWrapper(method: SmaliEngine.MutableMethod) {
        method.body.clear()
        method.body.addAll(listOf(
            "    .registers 3",
            "",
            "    const/4 v0, 0x0",
            "",
            "    invoke-virtual {p0, p1, v0}, Landroid/app/ApplicationPackageManager;->hasSystemFeature(Ljava/lang/String;I)Z",
            "",
            "    move-result v0",
            "",
            "    return v0"
        ))
    }

    /**
     * Xóa khối hook KaoriFeatureOverrides trong hasSystemFeature(String, I).
     *
     * Phạm vi xóa:
     * - Bắt đầu: dòng `currentPackageName` (hoặc lùi lại bao trùm `#Start KaoriosHook` nếu liền kề).
     * - Kết thúc: label orphan `:cond_*` / `:goto_*` ngay sau `return` của hook
     *   (skip dòng trống/comment giữa hai bên — match cả `:cond_kaori_override` lẫn `:cond_13`
     *   xuất hiện sau build cycle baksmali đổi tên label). Tiến thêm để bao `#End KaoriosHook`
     *   nếu có.
     */
    fun removeKaoriFeatureOverridesBlock(method: SmaliEngine.MutableMethod) {
        val anchorIdx = method.body.indexOfFirst {
            it.contains("Landroid/app/ActivityThread;->currentPackageName()Ljava/lang/String;")
        }
        if (anchorIdx < 0) return

        // Mở rộng start lùi để bao `#Start KaoriosHook` (bỏ qua dòng trống trung gian).
        var startIdx = anchorIdx
        run {
            var probe = anchorIdx - 1
            while (probe >= 0) {
                val t = method.body[probe].trim()
                if (t.isEmpty()) { probe--; continue }
                if (t == "#Start KaoriosHook") { startIdx = probe }
                break
            }
        }

        val coreEnd = findEndOfKaoriFeatureOverridesBlock(method.body, anchorIdx)
        if (coreEnd < 0) return

        // Mở rộng end tới để bao `#End KaoriosHook` (bỏ qua dòng trống trung gian).
        var endIdx = coreEnd
        run {
            var probe = coreEnd + 1
            while (probe < method.body.size) {
                val t = method.body[probe].trim()
                if (t.isEmpty()) { probe++; continue }
                if (t == "#End KaoriosHook") { endIdx = probe }
                break
            }
        }

        val toRemove = endIdx - startIdx + 1
        repeat(toRemove) { method.body.removeAt(startIdx) }
    }

    /**
     * Xóa mọi dòng `invoke-static {vN|pN}, ...KaoriPropsUtils;->KaoriProps(Landroid/content/Context;)V`
     * trong method (hook KaoriosToolbox 1.0.9 trên Instrumentation.newApplication). No-op nếu không có.
     */
    fun removeKaoriPropsInvokeLines(method: MutableMethod) {
        for (i in method.body.indices.reversed()) {
            if (KAORI_PROPS_INVOKE_RE.matches(method.body[i])) {
                method.body.removeAt(i)
            }
        }
    }

    private val KAORI_PROPS_INVOKE_RE = Regex(
        """^\s*invoke-static\s+\{[vp]\d+\},\s*Lcom/android/internal/util/kaorios/KaoriPropsUtils;->KaoriProps\(Landroid/content/Context;\)V\s*$"""
    )

    private fun findEndOfKaoriFeatureOverridesBlock(body: List<String>, startIdx: Int): Int {
        // Hook kết thúc tại label orphan `:cond_*` / `:goto_*` xuất hiện ngay sau `return` của hook
        // (cho phép dòng trống/comment xen giữa — chịu được cả label đổi tên sau build cycle).
        var i = startIdx
        while (i < body.size) {
            val line = body[i].trim()
            if (line.startsWith("return ")) {
                var j = i + 1
                while (j < body.size) {
                    val tn = body[j].trim()
                    if (tn.isEmpty() || tn.startsWith("#") || tn.startsWith(".line")) { j++; continue }
                    if (tn.startsWith(":cond_") || tn.startsWith(":goto_")) return j
                    break
                }
                return i
            }
            i++
        }
        return -1
    }

    /** Check xem body method có chứa từ "kaorios" (case-insensitive) không. */
    fun bodyContainsKaorios(method: SmaliEngine.MutableMethod): Boolean {
        return method.body.any { it.lowercase().contains("kaorios") }
    }

    // --- Helpers ---

    private fun wrapHookBlock(block: String): List<String> {
        val inner = block.trim('\n').lines().map { line ->
            if (line.isBlank()) line else "    $line"
        }
        return listOf("    #Start KaoriosHook") + inner + listOf("    #End KaoriosHook")
    }

    fun methodName(method: MutableMethod): String {
        val m = Regex("""\.method[^(]*\s+([\w$<>\-]+)(\([^)]*\)[^\s]+)""").find(method.header)
        return if (m != null) "${m.groupValues[1]}${m.groupValues[2]}" else "<unknown>"
    }

    fun methodSignature(header: String): String {
        val m = Regex("""([\w$<>\-]+)(\([^)]*\)[^\s]+)""").find(header) ?: return ""
        return m.groupValues[1] + m.groupValues[2]
    }

    /** Đếm số register chứa params (kể cả wide là 2) + 1 cho `this` nếu không static. */
    fun paramRegisterCount(header: String, signature: String): Int {
        val isStatic = header.contains(" static ")
        val inside = signature.substringAfter('(').substringBefore(')')
        var n = if (isStatic) 0 else 1
        var i = 0
        while (i < inside.length) {
            when (inside[i]) {
                'L' -> {
                    val end = inside.indexOf(';', i)
                    n += 1; i = end + 1
                }
                '[' -> {
                    var j = i
                    while (j < inside.length && inside[j] == '[') j++
                    if (j < inside.length && inside[j] == 'L') {
                        i = inside.indexOf(';', j) + 1
                    } else {
                        i = j + 1
                    }
                    n += 1
                }
                'J', 'D' -> { n += 2; i++ }
                else -> { n += 1; i++ }
            }
        }
        return n
    }
}
