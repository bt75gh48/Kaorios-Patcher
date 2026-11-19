package com.kaorios.patcher.data.dex

import com.kaorios.patcher.data.log.LogSink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Phân phối lại smali đã giải nén vào các thư mục `classes/`, `classes2/`, `classes3/`… theo
 * giới hạn method/field count (mặc định 64000 — giới hạn dex cổ điển).
 *
 * Port logic từ `Dex-Redivision_template/MainActivity.kt`:
 * - Parse mỗi file smali → set hash64 (FNV-1a) của method + field refs (khai báo + sử dụng qua `->`).
 * - Sort theo relative path; ties break bằng size giảm dần.
 * - Bin-packing: thử bucket cuối (locality), rồi các bucket khác, cuối cùng tạo bucket mới.
 * - Sort buckets theo số method giảm dần, đặt tên `classes`, `classes2`, `classes3`, …
 * - Move file bằng rename (fallback copy+delete).
 */
object DexRedivision {

    private const val TAG = "DexRedivision"
    private const val FNV_OFFSET: Long = -3750763034362895579L
    private const val FNV_PRIME: Long = 1099511628211L

    fun hash64(s: String): Long {
        var h = FNV_OFFSET
        for (c in s) { h = h xor c.code.toLong(); h *= FNV_PRIME }
        return h
    }

    data class SmaliStats(
        val file: File,
        val methods: HashSet<Long> = HashSet(),
        val fields: HashSet<Long> = HashSet()
    ) {
        fun totalSize() = methods.size + fields.size
    }

    data class DexBucket(
        val methods: HashSet<Long> = HashSet(),
        val fields: HashSet<Long> = HashSet(),
        val files: MutableList<SmaliStats> = mutableListOf()
    ) {
        fun addFile(sf: SmaliStats) {
            methods.addAll(sf.methods); fields.addAll(sf.fields); files.add(sf)
        }
    }

    /**
     * Phân phối toàn bộ smali trong `<dexDecompDir>/<mergedSubdirName>/` thành nhiều thư mục
     * `<dexDecompDir>/classes[N]/`. Sau khi xong, thư mục merged gốc bị xoá.
     *
     * @return tên các thư mục đã tạo (theo thứ tự — `classes` nhiều method nhất ở đầu).
     */
    suspend fun redivide(
        dexDecompDir: File,
        mergedSubdirName: String,
        limit: Int,
        onProgress: (stage: String, current: Int, total: Int) -> Unit = { _, _, _ -> }
    ): List<String> = withContext(Dispatchers.IO) {
        val mergedRoot = File(dexDecompDir, mergedSubdirName)
        require(mergedRoot.isDirectory) { "Merged dir không tồn tại: $mergedRoot" }

        // 1) List all smali
        val allFiles = mergedRoot.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".smali", ignoreCase = true) }
            .toList()
        val total = allFiles.size
        LogSink.i(TAG, "Parsing $total smali files, limit=$limit")
        if (total == 0) error("Không có smali nào trong $mergedRoot")
        onProgress("Parse smali", 0, total)

        // 2) Parse method + field refs
        val stats = ArrayList<SmaliStats>(total)
        allFiles.forEachIndexed { i, f ->
            stats.add(parseSmali(f))
            val done = i + 1
            if (done % 500 == 0 || done == total) onProgress("Parse smali", done, total)
        }

        // 3) Sort: rel path, ties break by size desc
        stats.sortWith(Comparator { a, b ->
            val pa = a.file.relativeTo(mergedRoot).path
            val pb = b.file.relativeTo(mergedRoot).path
            val c = pa.compareTo(pb)
            if (c != 0) c else b.totalSize().compareTo(a.totalSize())
        })

        // 4) Bin-packing
        onProgress("Phân phối dex", 0, total)
        val buckets = ArrayList<DexBucket>()
        for ((idx, sf) in stats.withIndex()) {
            var added = false
            if (buckets.isNotEmpty()) {
                val last = buckets.last()
                if (canFit(last, sf, limit)) { last.addFile(sf); added = true }
            }
            if (!added) {
                for (b in buckets) {
                    if (canFit(b, sf, limit)) { b.addFile(sf); added = true; break }
                }
            }
            if (!added) {
                val nb = DexBucket()
                nb.addFile(sf); buckets.add(nb)
            }
            if ((idx + 1) % 1000 == 0) onProgress("Phân phối dex", idx + 1, total)
        }
        // Sort bucket by method count desc — bucket lớn nhất = classes.
        buckets.sortByDescending { it.methods.size }

        val names = (1..buckets.size).map { i -> if (i == 1) "classes" else "classes$i" }
        LogSink.i(TAG, "Created ${buckets.size} bucket(s): " +
            buckets.mapIndexed { i, b -> "${names[i]}(m=${b.methods.size},f=${b.fields.size})" }
                .joinToString())

        // 5) Di chuyển file sang layout mới. Dùng thư mục tạm để tránh collision với `classes/` đang tồn tại.
        val tempRoot = File(dexDecompDir, ".rediv_tmp").apply { deleteRecursively(); mkdirs() }
        val totalMove = buckets.sumOf { it.files.size }
        var moved = 0
        onProgress("Move smali", 0, totalMove)

        buckets.forEachIndexed { i, b ->
            val dexDir = File(tempRoot, names[i]).apply { mkdirs() }
            for (sf in b.files) {
                val rel = sf.file.relativeTo(mergedRoot).path
                val dest = File(dexDir, rel)
                dest.parentFile?.mkdirs()
                if (!sf.file.renameTo(dest)) {
                    sf.file.copyTo(dest, overwrite = true)
                    sf.file.delete()
                }
                moved++
                if (moved % 500 == 0 || moved == totalMove) onProgress("Move smali", moved, totalMove)
            }
        }

        // 6) Xóa mergedRoot cũ (đã rỗng), move các thư mục trong tempRoot ra ngang với dexDecompDir.
        mergedRoot.deleteRecursively()
        tempRoot.listFiles()?.forEach { sub ->
            val target = File(dexDecompDir, sub.name)
            if (!sub.renameTo(target)) {
                sub.copyRecursively(target, overwrite = true)
                sub.deleteRecursively()
            }
        }
        tempRoot.deleteRecursively()

        LogSink.i(TAG, "Redivide xong — ${names.size} dex folder")
        names
    }

    // -------- Parsers & bin-packing helpers (port 1:1 từ template) --------

    fun parseSmali(file: File): SmaliStats {
        val sf = SmaliStats(file)
        var currentClass: String? = null
        file.bufferedReader().useLines { lines ->
            for (rawLine in lines) {
                var t = rawLine.trim()
                val hashIdx = t.indexOf('#')
                if (hashIdx >= 0) t = t.substring(0, hashIdx).trim()
                if (t.isEmpty()) continue

                if (t.startsWith(".class ")) {
                    val idx = t.lastIndexOf(' ')
                    if (idx > 0) currentClass = t.substring(idx + 1)
                } else if (currentClass != null) {
                    if (t.startsWith(".field ")) {
                        val colonIdx = t.indexOf(':')
                        if (colonIdx > 0) {
                            var start = t.substring(0, colonIdx).lastIndexOf(' ')
                            if (start < 0) start = 0
                            var end = t.indexOf(' ', colonIdx)
                            if (end == -1) end = t.length
                            if (start >= 0 && start < end) {
                                val name = t.substring(start + 1, end)
                                sf.fields.add(hash64("$currentClass->$name"))
                            }
                        }
                    } else if (t.startsWith(".method ")) {
                        val parenIdx = t.indexOf('(')
                        if (parenIdx > 0) {
                            val start = t.substring(0, parenIdx).lastIndexOf(' ')
                            var end = t.indexOf(' ', parenIdx)
                            if (end == -1) end = t.length
                            if (start >= 0 && start < end) {
                                val name = t.substring(start + 1, end)
                                sf.methods.add(hash64("$currentClass->$name"))
                            }
                        }
                    }
                }

                // External references via `->`
                var arrow = t.indexOf("->")
                while (arrow > 0) {
                    var start = arrow
                    while (start > 0 && " ,{}(\"".indexOf(t[start - 1]) == -1) start--
                    var end = arrow + 2
                    while (end < t.length && " ,{}\"'".indexOf(t[end]) == -1) end++
                    if (start < arrow) {
                        val ref = t.substring(start, end)
                        if (ref.indexOf('(') > 0) sf.methods.add(hash64(ref))
                        else sf.fields.add(hash64(ref))
                    }
                    arrow = t.indexOf("->", end)
                }
            }
        }
        return sf
    }

    private fun canFit(target: DexBucket, sf: SmaliStats, limit: Int): Boolean {
        val curM = target.methods.size
        var newM = 0
        for (m in sf.methods) {
            if (!target.methods.contains(m)) {
                newM++
                if (curM + newM > limit) return false
            }
        }
        val curF = target.fields.size
        var newF = 0
        for (f in sf.fields) {
            if (!target.fields.contains(f)) {
                newF++
                if (curF + newF > limit) return false
            }
        }
        return true
    }
}
