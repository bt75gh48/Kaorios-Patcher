package com.kaorios.patcher.editor

import android.content.Context
import com.kaorios.patcher.data.log.LogSink
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/** Danh sách opcodes smali tải từ assets (smali_instructions.json). */
object SmaliInstructionRepo {
    @Volatile private var cache: List<String>? = null

    fun load(ctx: Context): List<String> {
        cache?.let { return it }
        synchronized(this) {
            cache?.let { return it }
            val list = runCatching {
                ctx.assets.open("smali_instructions.json").use { ins ->
                    val text = ins.bufferedReader().readText()
                    Json.decodeFromString(ListSerializer(String.serializer()), text)
                }
            }.onFailure { LogSink.e("SmaliInstructionRepo", "load failed", it) }
                .getOrDefault(emptyList())
            cache = list
            return list
        }
    }
}
