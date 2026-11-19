package com.kaorios.patcher.data.jar

import com.kaorios.patcher.data.log.LogSink
import com.kaorios.patcher.util.FileIo
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** Giải nén/đóng gói file .jar bằng java.util.zip, giữ nguyên cấu trúc entries. */
object JarArchiver {

    /** Trả về số entry đã giải nén. */
    fun extract(src: File, outDir: File): Int {
        FileIo.ensureDir(outDir)
        var count = 0
        ZipFile(src).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val e = entries.nextElement()
                val normalized = e.name.replace('\\', '/')
                val dest = File(outDir, normalized)
                if (!dest.canonicalPath.startsWith(outDir.canonicalPath)) {
                    LogSink.w("JarArchiver", "Skipped zip-slip entry: $normalized")
                    continue
                }
                if (e.isDirectory) {
                    FileIo.ensureDir(dest)
                } else {
                    dest.parentFile?.let(FileIo::ensureDir)
                    zip.getInputStream(e).use { ins ->
                        BufferedOutputStream(FileOutputStream(dest)).use { out ->
                            FileIo.copy(ins, out)
                        }
                    }
                }
                count++
            }
        }
        LogSink.detail("JarArchiver", "extract · ${src.name} → $count entries")
        LogSink.d("JarArchiver", "  ${outDir.absolutePath}")
        return count
    }

    /**
     * Đóng gói thư mục thành jar.
     * Mặc định **STORED** (không nén, đúng method zip).
     * `compress = true` → DEFLATED (nén mặc định).
     */
    fun pack(srcDir: File, outJar: File, compress: Boolean = false): Int {
        outJar.parentFile?.let(FileIo::ensureDir)
        var count = 0
        BufferedOutputStream(FileOutputStream(outJar)).use { fileOut ->
            ZipOutputStream(fileOut).use { zip ->
                if (compress) {
                    zip.setLevel(Deflater.DEFAULT_COMPRESSION)
                    writeDirectoryDeflated(zip, srcDir, srcDir) { count++ }
                } else {
                    writeDirectoryStored(zip, srcDir, srcDir) { count++ }
                }
            }
        }
        LogSink.detail("JarArchiver", "pack · ${outJar.name} ($count entries)")
        return count
    }

    private fun writeDirectoryStored(
        zip: ZipOutputStream,
        root: File,
        current: File,
        onEntry: () -> Unit
    ) {
        val rootPath = root.canonicalPath
        current.listFiles()?.sortedBy { it.name }?.forEach { f ->
            val relPath = f.canonicalPath.removePrefix(rootPath).trimStart(File.separatorChar)
                .replace('\\', '/')
            if (relPath.isEmpty()) return@forEach
            if (f.isDirectory) {
                ZipStored.putStoredDirectory(zip, relPath)
                onEntry()
                writeDirectoryStored(zip, root, f, onEntry)
            } else {
                ZipStored.putStoredFile(zip, relPath, f)
                onEntry()
            }
        }
    }

    private fun writeDirectoryDeflated(
        zip: ZipOutputStream,
        root: File,
        current: File,
        onEntry: () -> Unit
    ) {
        val rootPath = root.canonicalPath
        current.listFiles()?.sortedBy { it.name }?.forEach { f ->
            val relPath = f.canonicalPath.removePrefix(rootPath).trimStart(File.separatorChar)
                .replace('\\', '/')
            if (relPath.isEmpty()) return@forEach
            if (f.isDirectory) {
                val entry = ZipEntry("$relPath/")
                zip.putNextEntry(entry)
                zip.closeEntry()
                onEntry()
                writeDirectoryDeflated(zip, root, f, onEntry)
            } else {
                val entry = ZipEntry(relPath)
                zip.putNextEntry(entry)
                f.inputStream().use { ins -> FileIo.copy(ins, zip) }
                zip.closeEntry()
                onEntry()
            }
        }
    }
}
