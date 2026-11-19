package com.kaorios.patcher.data.jar

import com.kaorios.patcher.util.FileIo
import java.io.File
import java.io.FileInputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Ghi entry zip method **STORED** (không nén), đúng theo định dạng zip — cần CRC + size trước khi ghi payload.
 */
object ZipStored {

    private const val BUF = 256 * 1024

    /** CRC32 và kích thước file (hai lần đọc file: một lần hash, một lần ghi). */
    fun crc32AndSize(file: File): Pair<Long, Long> {
        val crc = CRC32()
        var size = 0L
        FileInputStream(file).use { ins ->
            val buf = ByteArray(BUF)
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                crc.update(buf, 0, n)
                size += n
            }
        }
        return crc.value to size
    }

    fun putStoredFile(zip: ZipOutputStream, entryName: String, file: File, lastModified: Long = file.lastModified()) {
        val (crcVal, size) = crc32AndSize(file)
        val entry = ZipEntry(entryName.replace('\\', '/')).apply {
            method = ZipEntry.STORED
            this.size = size
            crc = crcVal
            if (lastModified > 0L) time = lastModified
        }
        zip.putNextEntry(entry)
        FileInputStream(file).use { ins -> FileIo.copy(ins, zip) }
        zip.closeEntry()
    }

    fun putStoredDirectory(zip: ZipOutputStream, entryName: String) {
        val name = entryName.replace('\\', '/').let { if (it.endsWith("/")) it else "$it/" }
        val entry = ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = 0
            crc = 0
        }
        zip.putNextEntry(entry)
        zip.closeEntry()
    }

    /** Ghi một entry STORED từ mảng byte đã có sẵn (vd. copy từ zip khác). */
    fun putStoredBytes(
        zip: ZipOutputStream,
        entryName: String,
        data: ByteArray,
        lastModified: Long = System.currentTimeMillis()
    ) {
        val crc32 = CRC32()
        crc32.update(data)
        val crcVal = crc32.value
        val entry = ZipEntry(entryName.replace('\\', '/')).apply {
            method = ZipEntry.STORED
            size = data.size.toLong()
            crc = crcVal
            if (lastModified > 0L) time = lastModified
        }
        zip.putNextEntry(entry)
        zip.write(data)
        zip.closeEntry()
    }
}
