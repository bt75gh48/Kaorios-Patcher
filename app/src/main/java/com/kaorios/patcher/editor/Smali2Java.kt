package com.kaorios.patcher.editor

import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.writer.io.FileDataStore
import com.android.tools.smali.dexlib2.writer.pool.DexPool
import com.android.tools.smali.smali.SmaliTestUtils
import com.google.common.collect.ImmutableList
import com.kaorios.patcher.data.jar.ZipStored
import com.kaorios.patcher.data.log.LogSink
import jadx.api.JadxArgs
import jadx.api.JadxDecompiler
import jadx.api.JavaClass
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipOutputStream

/**
 * Dịch 1+ file smali sang Java qua `jadx-core` + plugin `jadx-dex-input` / `jadx-java-convert`.
 *
 * Nhận nhiều smali để jadx có đủ context decompile inner/anonymous class (A, A$1, A$Builder…).
 * DexPool intern tất cả class vào 1 dex, sau đó đóng thành .jar rồi feed cho jadx.
 */
object Smali2Java {

    private const val TAG = "Smali2Java"

    data class Result(
        val primaryCode: String,
        val classCount: Int,
        val primaryType: String
    )

    /**
     * @param smaliTexts Danh sách nội dung smali. Mỗi phần tử là 1 class độc lập.
     * @param dexVersion Version dex để chọn Opcodes.
     * @param primaryTypeHint Type descriptor class chính cần trả code (ví dụ `LmodsCenter;`).
     *                        Nếu null/rỗng, trả code của class ĐẦU TIÊN.
     */
    fun translate(
        smaliTexts: List<String>,
        dexVersion: Int,
        primaryTypeHint: String? = null
    ): Result {
        val nonBlank = smaliTexts.filter { it.isNotBlank() }
        if (nonBlank.isEmpty()) error("Không có nội dung smali nào để dịch.")
        LogSink.i(TAG, "translate start: ${nonBlank.size} smali input(s), dexVersion=$dexVersion, hint=$primaryTypeHint")

        val tmpDex = File.createTempFile("kaorios_s2j_", ".dex")
        val tmpJar = File.createTempFile("kaorios_s2j_", ".jar")
        val outDir = File(tmpDex.parentFile, tmpDex.nameWithoutExtension + "_out").apply {
            deleteRecursively(); mkdirs()
        }
        var decompiler: JadxDecompiler? = null
        try {
            val opcodes = try {
                Opcodes.forDexVersion(dexVersion)
            } catch (t: Throwable) {
                LogSink.w(TAG, "forDexVersion($dexVersion) failed → fallback 39", t); Opcodes.forDexVersion(39)
            }
            LogSink.i(TAG, "opcodes.api=${opcodes.api}")

            // 1) Compile mọi smali → ClassDef
            val classDefs = mutableListOf<ClassDef>()
            for ((i, text) in nonBlank.withIndex()) {
                try {
                    val cd = SmaliTestUtils.compileSmali(text, opcodes.api)
                    classDefs.add(cd)
                    LogSink.i(TAG, "  compile [$i] OK → ${cd.type}")
                } catch (t: Throwable) {
                    LogSink.w(TAG, "  compile [$i] FAILED, skip: ${t.message}", t)
                }
            }
            if (classDefs.isEmpty()) error("Không compile được smali nào.")

            // 2) DexPool intern tất cả → ghi file dex
            val pool = DexPool(opcodes)
            classDefs.forEach { pool.internClass(it) }
            pool.writeTo(FileDataStore(tmpDex))
            val dexSize = tmpDex.length()
            LogSink.i(TAG, "DexPool merged -> ${tmpDex.absolutePath} (${dexSize}B, ${classDefs.size} classes)")
            if (dexSize <= 0L) error("Dex tạm 0 bytes.")

            val verifyCount = runCatching {
                DexBackedDexFile.fromInputStream(opcodes, ByteArrayInputStream(tmpDex.readBytes()))
                    .classes.size
            }.getOrDefault(-1)
            LogSink.i(TAG, "verify(dexlib2) classes=$verifyCount")

            // 3) Wrap vào jar (jadx-java-convert ổn định với jar).
            val dexBytes = tmpDex.readBytes()
            ZipOutputStream(FileOutputStream(tmpJar)).use { zip ->
                ZipStored.putStoredBytes(zip, "classes.dex", dexBytes)
            }

            // 4) Jadx
            val args = JadxArgs().apply {
                setInputFiles(ImmutableList.of(tmpJar))
                setOutDir(outDir)
                setSkipResources(true)
                setShowInconsistentCode(true)
                tryCall("setDeobfuscationOn", false)
                tryCall("setRenameValid", false)
                tryCall("setRenameCaseSensitive", false)
                tryCall("setRenamePrintable", false)
                tryCall("setUseKotlinMethodsForVarNames", false)
            }
            decompiler = JadxDecompiler(args)
            decompiler.load()

            val classes = pickClasses(decompiler)
            LogSink.i(TAG, "jadx classes = ${classes.size}")
            if (classes.isEmpty()) {
                val internal = dumpInternalClassCount(decompiler)
                error("jadx không trả class nào (internal=$internal). Xem log chi tiết.")
            }

            // 5) Chọn primary class theo hint (nếu có), fallback class đầu tiên.
            val primary = selectPrimary(classes, primaryTypeHint)
            LogSink.i(TAG, "decompile primary=${primary.fullName} (rawName=${primary.rawName})")
            primary.decompile()
            val code = primary.code ?: ""
            if (code.isBlank()) error("jadx trả code rỗng cho ${primary.fullName}.")
            LogSink.i(TAG, "decompile OK — codeLen=${code.length}")
            return Result(code, classes.size, primary.rawName ?: primary.fullName)
        } catch (t: Throwable) {
            LogSink.e(TAG, "translate FAILED: ${t.message}", t)
            throw t
        } finally {
            runCatching { decompiler?.close() }
            tmpDex.delete(); tmpJar.delete()
            outDir.deleteRecursively()
        }
    }

    private fun selectPrimary(classes: List<JavaClass>, hint: String?): JavaClass {
        if (hint.isNullOrBlank()) return classes.first()
        // Match rawName == hint ("LmodsCenter;"), hoặc fullName tương đương.
        val hintInternal = hint.removePrefix("L").removeSuffix(";").replace('/', '.')
        return classes.firstOrNull { cls ->
            cls.rawName == hint ||
                cls.fullName == hintInternal ||
                (cls.fullName?.endsWith(hintInternal.substringAfterLast('.')) == true)
        } ?: classes.first()
    }

    private fun Any.tryCall(method: String, boolValue: Boolean) {
        runCatching {
            this.javaClass.getMethod(method, java.lang.Boolean.TYPE).invoke(this, boolValue)
        }
    }

    private fun pickClasses(decompiler: JadxDecompiler): List<JavaClass> {
        runCatching { decompiler.classes }.getOrNull()?.let { if (it.isNotEmpty()) return it }
        runCatching {
            val m = decompiler.javaClass.methods.firstOrNull { it.name == "getClassesWithInners" }
            @Suppress("UNCHECKED_CAST")
            val list = m?.invoke(decompiler) as? List<JavaClass>
            if (!list.isNullOrEmpty()) return list
        }
        runCatching {
            val root = decompiler.javaClass.methods.firstOrNull { it.name == "getRoot" }
                ?.invoke(decompiler) ?: return@runCatching
            val rootClasses = root.javaClass.methods
                .firstOrNull { it.name == "getClasses" && it.parameterTypes.isEmpty() }
                ?.invoke(root) as? List<*> ?: return@runCatching
            if (rootClasses.isEmpty()) return@runCatching
            val convert = decompiler.javaClass.declaredMethods
                .firstOrNull { it.name.contains("convertClass", ignoreCase = true) }
                ?.apply { isAccessible = true }
            if (convert != null) {
                val mapped = rootClasses.mapNotNull { cn ->
                    runCatching { convert.invoke(decompiler, cn) as? JavaClass }.getOrNull()
                }
                if (mapped.isNotEmpty()) return mapped
            }
        }
        return emptyList()
    }

    private fun dumpInternalClassCount(decompiler: JadxDecompiler): Int =
        runCatching {
            val root = decompiler.javaClass.methods.firstOrNull { it.name == "getRoot" }
                ?.invoke(decompiler)
            val list = root?.javaClass?.methods
                ?.firstOrNull { it.name == "getClasses" && it.parameterTypes.isEmpty() }
                ?.invoke(root) as? List<*>
            list?.size ?: -1
        }.getOrDefault(-1)
}
