package com.kaorios.patcher.data.framework

import com.kaorios.patcher.data.log.LogSink
import java.io.File

/**
 * Đọc thư mục `/system/framework` trực tiếp bằng File API.
 * Trên đa số ROM Android 13, thư mục này có mode `drwxr-xr-x` (readable by others).
 * SELinux có thể chặn đọc một số file — caller nên try/catch.
 */
object SystemFrameworkReader {
    private val ROOT = File("/system/framework")

    data class Entry(val file: File, val sizeBytes: Long)

    fun isAvailable(): Boolean = ROOT.exists() && ROOT.canRead()

    /** List tất cả file .jar trong /system/framework (không đệ quy vào arch folders). */
    fun listJars(): List<Entry> {
        if (!isAvailable()) {
            LogSink.w("SystemFramework", "/system/framework not readable")
            return emptyList()
        }
        return runCatching {
            ROOT.listFiles { f -> f.isFile && f.name.endsWith(".jar", ignoreCase = true) }
                ?.map { Entry(it, it.length()) }
                ?.sortedBy { it.file.name }
                ?: emptyList()
        }.onFailure { LogSink.e("SystemFramework", "listJars failed", it) }
            .getOrDefault(emptyList())
    }

    /** Trả về 1 file .jar cụ thể (framework.jar / services.jar). */
    fun pick(name: String): File? {
        val candidate = File(ROOT, name)
        return if (candidate.isFile && candidate.canRead()) candidate else null
    }
}
