package com.kaorios.patcher.data.dex

import com.kaorios.patcher.data.log.LogSink
import com.android.tools.smali.smali.SmaliOptions
import java.io.File
import java.lang.reflect.Method

/**
 * Compile smali directory → .dex bytes, dùng `com.android.tools.smali.smali.Smali` (smali 3.0.x).
 *
 * API trong smali 3.0.x:
 * ```
 * public static boolean assemble(SmaliOptions options, List<String> input)
 * public static boolean assemble(SmaliOptions options, String... input)
 * ```
 *
 * Options quan trọng:
 * - `apiLevel`: Android API level tương ứng dex version.
 * - `outputDexFile`: path FILE dex output (bắt buộc — assemble ghi kết quả ra đây).
 * - `jobs`: số thread compile song song.
 * - `verboseErrors`: bật log chi tiết lỗi smali.
 */
object Smali {

    private const val TAG = "Smali"

    /**
     * Biên dịch toàn bộ file `.smali` trong [smaliRoot] thành 1 ByteArray dex.
     *
     * Internals:
     *  1. List tất cả .smali qua walkTopDown.
     *  2. Tạo file dex tạm (cache dir nếu truyền ctx — ở đây dùng `smaliRoot.parentFile/tmp.dex`).
     *  3. Gọi `Smali.assemble(options, paths)` (reflection để tương thích nhiều subversion 3.0.x).
     *  4. Đọc lại file dex tạm, trả ByteArray, xóa file tạm.
     */
    fun assemble(smaliRoot: File, dexVersion: Int): ByteArray {
        val t0 = System.currentTimeMillis()
        LogSink.i(TAG, "assemble() start — root=${smaliRoot.absolutePath}, dexVersion=$dexVersion")

        require(smaliRoot.isDirectory) { "smaliRoot không phải thư mục: $smaliRoot" }

        val files = smaliRoot.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".smali", ignoreCase = true) }
            .toList()
        LogSink.i(TAG, "  found ${files.size} .smali files")
        if (files.isEmpty()) error("Không có file .smali nào dưới $smaliRoot")

        // Sample 3 file để log (giúp trace nếu có file rỗng / sai path)
        files.take(3).forEach { LogSink.d(TAG, "  sample: ${it.absolutePath} (${it.length()}B)") }
        val zeroByte = files.firstOrNull { it.length() == 0L }
        if (zeroByte != null) LogSink.w(TAG, "  WARN: có file .smali 0 bytes: ${zeroByte.absolutePath}")

        val apiLvl = apiLevelForDexVersion(dexVersion)
        val tmpOut = File.createTempFile("kaorios_build_", ".dex", smaliRoot.parentFile ?: smaliRoot)
        LogSink.i(TAG, "  temp output: ${tmpOut.absolutePath} (apiLevel=$apiLvl, threads=${Runtime.getRuntime().availableProcessors()})")

        val options = SmaliOptions().apply {
            apiLevel = apiLvl
            outputDexFile = tmpOut.absolutePath
            jobs = Runtime.getRuntime().availableProcessors().coerceIn(1, 4)
            verboseErrors = true
            // Các flag khác giữ default (allowOdexInstructions=false, forceApi=false, v.v.)
        }
        dumpSmaliOptions(options)

        val paths: List<String> = files.map { it.absolutePath }

        // Hook System.err / System.out để capture log của smali (verboseErrors bỏ dòng tới stderr).
        val origErr = System.err
        val origOut = System.out
        val capBuf = java.io.ByteArrayOutputStream()
        val capStream = java.io.PrintStream(capBuf, true, "UTF-8")
        System.setErr(capStream)
        System.setOut(capStream)

        val ok = try {
            invokeAssemble(options, paths)
        } catch (t: Throwable) {
            tmpOut.delete()
            System.setErr(origErr); System.setOut(origOut)
            val capText = capBuf.toString("UTF-8").trim()
            if (capText.isNotEmpty()) LogSink.e(TAG, "  smali stderr/stdout:\n$capText")
            LogSink.e(TAG, "assemble throw: ${t.message}", t)
            throw t
        } finally {
            System.setErr(origErr); System.setOut(origOut)
        }
        val capText = capBuf.toString("UTF-8").trim()
        if (capText.isNotEmpty()) {
            // Giới hạn 4KB để tránh spam log.
            val limited = if (capText.length > 4000) capText.take(4000) + "\n…(truncated)" else capText
            LogSink.w(TAG, "  smali stderr/stdout output:\n$limited")
        }
        LogSink.i(TAG, "  Smali.assemble returned = $ok")

        if (!ok) {
            val size = tmpOut.length()
            tmpOut.delete()
            error("Smali.assemble() trả false — có lỗi smali (xem log dòng có tag smali). tmpSize=${size}B")
        }

        val bytes = tmpOut.readBytes()
        tmpOut.delete()
        val dt = System.currentTimeMillis() - t0
        LogSink.i(TAG, "assemble OK — output ${bytes.size} bytes in ${dt}ms")
        if (bytes.isEmpty()) error("Smali assemble tạo file 0 bytes.")
        return bytes
    }

    /**
     * Gọi `Smali.assemble(options, List<String>)` qua reflection.
     * Thử cả các variant: `(options, List<String>)`, `(options, String[])`.
     */
    private fun invokeAssemble(options: SmaliOptions, paths: List<String>): Boolean {
        val cls = Class.forName("com.android.tools.smali.smali.Smali")
        val candidates = cls.methods.filter { it.name == "assemble" }
        LogSink.d(TAG, "  Smali class found — assemble methods (${candidates.size}):")
        candidates.forEachIndexed { i, m ->
            LogSink.d(TAG, "    [$i] ${methodSig(m)}")
        }

        if (candidates.isEmpty()) error("Class com.android.tools.smali.smali.Smali không có method assemble")

        var lastError: Throwable? = null
        for (m in candidates) {
            val p = m.parameterTypes
            val variant: Pair<String, Array<Any?>>? = when {
                p.size == 2 && p[0] == SmaliOptions::class.java && p[1] == java.util.List::class.java ->
                    "List<String>" to arrayOf<Any?>(options, paths)

                p.size == 2 && p[0] == SmaliOptions::class.java && p[1] == Iterable::class.java ->
                    "Iterable<String>" to arrayOf<Any?>(options, paths)

                p.size == 2 && p[0] == SmaliOptions::class.java && p[1].isArray && p[1].componentType == String::class.java ->
                    "String[]" to arrayOf<Any?>(options, paths.toTypedArray())

                else -> null
            }
            if (variant == null) {
                LogSink.d(TAG, "    skip ${methodSig(m)} — không match signature đã biết")
                continue
            }
            LogSink.i(TAG, "  calling Smali.assemble(${variant.first}) with ${paths.size} paths…")
            try {
                val result = m.invoke(null, *variant.second)
                return when (result) {
                    is Boolean -> result
                    null -> {
                        LogSink.w(TAG, "  assemble() trả null (void method?) — giả định OK")
                        true
                    }
                    else -> {
                        LogSink.w(TAG, "  assemble() trả ${result.javaClass.simpleName} — giả định OK")
                        true
                    }
                }
            } catch (t: Throwable) {
                val root = rootCause(t)
                LogSink.e(TAG, "  invoke ${methodSig(m)} THREW: ${root.javaClass.simpleName}: ${root.message}", root)
                lastError = root
            }
        }
        if (lastError != null) throw lastError
        error("Không tìm thấy variant Smali.assemble(SmaliOptions, List<String>|String[]) tương thích")
    }

    private fun methodSig(m: Method): String {
        val params = m.parameterTypes.joinToString(", ") { it.simpleName }
        return "${m.returnType.simpleName} ${m.name}($params)"
    }

    private fun rootCause(t: Throwable): Throwable {
        var cur: Throwable = t
        while (cur.cause != null && cur.cause !== cur) cur = cur.cause!!
        return cur
    }

    private fun dumpSmaliOptions(o: SmaliOptions) {
        val dump = buildString {
            append("SmaliOptions{")
            append("apiLevel=").append(o.apiLevel).append(", ")
            append("outputDexFile=").append(o.outputDexFile).append(", ")
            append("jobs=").append(o.jobs).append(", ")
            append("verboseErrors=").append(o.verboseErrors)
            append("}")
        }
        LogSink.d(TAG, "  $dump")
    }

    /** Map dex version → android API level gần đúng. */
    private fun apiLevelForDexVersion(dexVersion: Int): Int = when {
        dexVersion >= 41 -> 35
        dexVersion >= 40 -> 34
        dexVersion >= 39 -> 29
        dexVersion >= 38 -> 26
        dexVersion >= 37 -> 24
        dexVersion >= 35 -> 21
        else -> 29
    }
}
