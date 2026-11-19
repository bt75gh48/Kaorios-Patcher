package com.kaorios.patcher.data.dex

import com.kaorios.patcher.data.log.LogSink
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.util.DexUtil
import java.io.ByteArrayInputStream
import java.io.File

object DexIo {
    const val DEFAULT_DEX_VERSION = 39

    data class LoadedDex(val version: Int, val dex: DexBackedDexFile)

    fun read(file: File): ByteArray = file.readBytes()

    fun load(bytes: ByteArray): LoadedDex {
        val version = try {
            DexUtil.verifyDexHeader(bytes, 0)
        } catch (t: Throwable) {
            LogSink.w("DexIo", "verifyDexHeader failed, fallback to $DEFAULT_DEX_VERSION", t)
            DEFAULT_DEX_VERSION
        }
        val dex = DexBackedDexFile.fromInputStream(
            Opcodes.forDexVersion(version),
            ByteArrayInputStream(bytes)
        )
        return LoadedDex(version, dex)
    }

    fun load(file: File): LoadedDex = load(read(file))
}
