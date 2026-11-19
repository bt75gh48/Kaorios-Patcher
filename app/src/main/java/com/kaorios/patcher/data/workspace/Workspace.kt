package com.kaorios.patcher.data.workspace

import android.content.Context
import com.kaorios.patcher.domain.model.WorkMode
import com.kaorios.patcher.util.FileIo
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Workspace {

    private val TS = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

    /**
     * Thư mục workspace — nằm trong internal storage của app.
     *
     * Dùng filesDir thay vì cacheDir để có thư mục riêng:
     * /data/data/com.kaorios.patcher/files/kaorios_work
     *
     * Ưu điểm so với externalFilesDir (Android/data/...):
     * - Nằm trong bộ nhớ trong → truy xuất nhanh hơn nhiều.
     * - Không cần quyền storage.
     * - Không bị xóa tự động khi hệ thống cần dung lượng.
     */
    fun rootDir(ctx: Context): File =
        FileIo.ensureDir(File(ctx.filesDir, "kaorios_work"))

    /**
     * Xóa toàn bộ workspace cũ rồi tạo thư mục session mới.
     * Đảm bảo không còn file decompile/jar của phiên trước chiếm dung lượng.
     */
    fun newSessionDir(ctx: Context, mode: WorkMode): File {
        val root = rootDir(ctx)
        root.listFiles()?.forEach { FileIo.deleteRecursively(it) }
        val tag = TS.format(Date())
        val dir = File(root, "${tag}_${mode.name.lowercase()}")
        FileIo.ensureDir(dir)
        return dir
    }

    fun outputDir(workDir: File): File = FileIo.ensureDir(File(workDir, "output"))

    fun jarDecompDir(workDir: File, logicalName: String): File =
        FileIo.ensureDir(File(workDir, "${logicalName}_jar_decomp"))

    fun dexDecompDir(workDir: File, logicalName: String): File =
        FileIo.ensureDir(File(workDir, "${logicalName}_dex_decomp"))

    fun workingJarCopy(workDir: File, logicalName: String): File =
        File(workDir, "${logicalName}.jar")
}
