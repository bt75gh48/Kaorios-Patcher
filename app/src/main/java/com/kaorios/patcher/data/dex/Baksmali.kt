package com.kaorios.patcher.data.dex

import com.kaorios.patcher.data.log.LogSink
import com.android.tools.smali.baksmali.Adaptors.ClassDefinition
import com.android.tools.smali.baksmali.BaksmaliOptions
import com.android.tools.smali.baksmali.formatter.BaksmaliWriter
import com.android.tools.smali.dexlib2.iface.ClassDef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.concurrent.atomic.AtomicInteger

/**
 * Baksmali thủ công song song: duyệt tất cả class trong DexBackedDexFile, ghi từng file `.smali`
 * trực tiếp qua BufferedWriter (không trung gian StringWriter), chạy parallel theo chunk để tận
 * dụng nhiều nhân CPU.
 *
 * Tham chiếu: `KaoriosManager/.../utils/ClassTree.java` (lines 277-297).
 */
object Baksmali {

    /**
     * @param dex bộ class đã load qua [DexIo.load]
     * @param outRoot thư mục đích
     * @param progress callback (current, total)
     * @return số class đã xuất
     */
    suspend fun decompile(
        dex: DexIo.LoadedDex,
        outRoot: File,
        progress: (current: Int, total: Int) -> Unit = { _, _ -> }
    ): Int = coroutineScope {
        val options = BaksmaliOptions()
        val classes = dex.dex.classes.toList()
        val total = classes.size
        if (total == 0) return@coroutineScope 0
        outRoot.mkdirs()

        // Chia theo parallelism của thiết bị. Giới hạn 4 workers cho dexlib2 để tránh OOM
        // khi compile frame.jar (~10k class, ~200MB RAM peak).
        val workers = Runtime.getRuntime().availableProcessors().coerceIn(2, 4)
        val chunkSize = (total + workers - 1) / workers
        val done = AtomicInteger(0)

        val reportStep = (total / 100).coerceAtLeast(25)
        val jobs = (0 until workers).map { w ->
            async(Dispatchers.IO) {
                val start = w * chunkSize
                val end = ((w + 1) * chunkSize).coerceAtMost(total)
                for (i in start until end) {
                    try {
                        writeClass(classes[i], outRoot, options)
                    } catch (t: Throwable) {
                        LogSink.e("Baksmali", "Failed to dump ${classes[i].type}", t)
                    }
                    val c = done.incrementAndGet()
                    if (c % reportStep == 0 || c == total) progress(c, total)
                }
            }
        }
        jobs.awaitAll()
        total
    }

    private fun writeClass(
        classDef: ClassDef,
        outRoot: File,
        options: BaksmaliOptions
    ) {
        val rawType = classDef.type
        val internalName = if (rawType.startsWith("L") && rawType.endsWith(";")) {
            rawType.substring(1, rawType.length - 1)
        } else rawType
        val relPath = "$internalName.smali"
        val outFile = File(outRoot, relPath)
        // Luôn mkdirs() trước khi mở file. Trước đây cache theo parent + worker song song
        // gây race: thread khác thấy path đã trong set nên bỏ mkdir trong khi thread đầu
        // chưa kịp tạo xong → FileNotFoundException (ENOENT).
        outFile.parentFile?.mkdirs()

        // Stream thẳng xuống file qua BufferedWriter — không dựng string 500KB-5MB tạm trong RAM.
        BufferedWriter(OutputStreamWriter(FileOutputStream(outFile), Charsets.UTF_8), 64 * 1024).use { bw ->
            BaksmaliWriter(bw).use { baks ->
                ClassDefinition(options, classDef).writeTo(baks)
            }
        }
    }
}
