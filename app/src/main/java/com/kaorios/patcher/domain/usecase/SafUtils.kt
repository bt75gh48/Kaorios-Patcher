package com.kaorios.patcher.domain.usecase

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.kaorios.patcher.data.log.LogSink
import com.kaorios.patcher.util.FileIo
import java.io.File

object SafUtils {

    fun exportFilesToTree(ctx: Context, treeUri: Uri, files: List<File>) {
        val root = DocumentFile.fromTreeUri(ctx, treeUri)
            ?: error("Cannot open tree uri $treeUri")
        files.forEach { src ->
            val existing = root.findFile(src.name)
            existing?.delete()
            val mime = mimeFor(src.name)
            // SAF createFile sẽ tự append extension nếu mime không khớp → dùng mime đúng theo ext,
            // fallback octet-stream (provider KHÔNG append ext cho mime này).
            val doc = root.createFile(mime, src.name)
                ?: root.createFile("application/octet-stream", src.name)
                ?: error("Cannot create file ${src.name} in $treeUri")
            ctx.contentResolver.openOutputStream(doc.uri)?.use { out ->
                src.inputStream().use { ins -> FileIo.copy(ins, out) }
            } ?: error("Cannot open OutputStream for ${doc.uri}")
            LogSink.i("Saf", "Exported ${src.name} (mime=$mime) -> ${doc.uri}")
        }
    }

    private fun mimeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "jar" -> "application/java-archive"
        "zip" -> "application/zip"
        "dex" -> "application/octet-stream"
        "apk" -> "application/vnd.android.package-archive"
        "json" -> "application/json"
        "txt", "log" -> "text/plain"
        else -> "application/octet-stream"
    }
}
