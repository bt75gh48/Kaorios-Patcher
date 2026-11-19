package com.kaorios.patcher.util

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.InputStream
import java.io.OutputStream

object FileIo {

    fun copy(input: InputStream, output: OutputStream): Long {
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            output.write(buf, 0, n)
            total += n
        }
        output.flush()
        return total
    }

    fun copyUriToFile(resolver: ContentResolver, uri: Uri, dest: File): Long {
        dest.parentFile?.mkdirs()
        resolver.openInputStream(uri)?.use { ins ->
            dest.outputStream().use { out -> return copy(ins, out) }
        }
        error("Cannot open input stream for uri=$uri")
    }

    fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? {
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) return c.getString(0)
            }
        }
        return null
    }

    fun deleteRecursively(f: File) {
        if (!f.exists()) return
        if (f.isDirectory) {
            f.listFiles()?.forEach(::deleteRecursively)
        }
        f.delete()
    }

    fun ensureDir(f: File): File {
        if (!f.exists()) f.mkdirs()
        return f
    }
}
