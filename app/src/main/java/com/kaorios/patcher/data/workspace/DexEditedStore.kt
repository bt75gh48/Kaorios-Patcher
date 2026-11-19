package com.kaorios.patcher.data.workspace

import com.kaorios.patcher.data.log.LogSink
import com.kaorios.patcher.domain.model.DexEditedEntry
import com.kaorios.patcher.domain.model.DexEditedMap
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

object DexEditedStore {
    const val FILE_NAME = "dex_edited.json"

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    fun file(dexDecompDir: File) = File(dexDecompDir, FILE_NAME)

    fun read(dexDecompDir: File): MutableMap<String, DexEditedEntry> {
        val f = file(dexDecompDir)
        if (!f.exists()) return linkedMapOf()
        return runCatching {
            json.decodeFromString<MutableMap<String, DexEditedEntry>>(f.readText())
        }.getOrElse {
            LogSink.e("DexEditedStore", "Failed to read ${f.absolutePath}", it)
            linkedMapOf()
        }
    }

    fun write(dexDecompDir: File, map: DexEditedMap) {
        val f = file(dexDecompDir)
        f.parentFile?.mkdirs()
        f.writeText(json.encodeToString(map))
    }

    fun setEditedFlag(dexDecompDir: File, dexName: String, value: Int) {
        val map = read(dexDecompDir)
        val existing = map[dexName] ?: DexEditedEntry()
        map[dexName] = existing.copy(value = value)
        write(dexDecompDir, map)
    }

    /**
     * Xoá entry [dexName] khỏi `dex_edited.json`. Dùng khi classesN bị xoá hoàn toàn
     * (vd remove hook khiến folder rỗng) để build không cố compile lại.
     */
    fun removeEntry(dexDecompDir: File, dexName: String): Boolean {
        val map = read(dexDecompDir)
        if (map.remove(dexName) == null) return false
        write(dexDecompDir, map)
        return true
    }
}
