package com.kaorios.patcher.domain.actions

import com.kaorios.patcher.data.log.LogSink
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Lưu metadata sau khi các script chạy xong. Dùng để:
 * - Nhớ tag đã dùng cho script KaoriosToolbox / UpdateKaoriosDex trong phiên hiện tại.
 * - Khi Build module, chọn tag phù hợp theo script người dùng đã chạy.
 *
 * File lưu tại `<workDir>/action_state.json`.
 */
object ActionState {

    const val SCRIPT_ID_TOOLBOX_2036 = "kaoriostoolbox_203"
    const val SCRIPT_ID_UPDATE_KAORIOS_DEX = "update_kaorios_dex"

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private const val FILE_NAME = "action_state.json"

    @Serializable
    data class ScriptEntry(val tag: String)

    @Serializable
    data class State(val scripts: MutableMap<String, ScriptEntry> = mutableMapOf())

    fun file(workDir: File): File = File(workDir, FILE_NAME)

    @Synchronized
    fun read(workDir: File): State {
        val f = file(workDir)
        if (!f.exists()) return State()
        return runCatching { json.decodeFromString<State>(f.readText()) }
            .onFailure { LogSink.w("ActionState", "Parse $FILE_NAME failed", it) }
            .getOrElse { State() }
    }

    @Synchronized
    fun write(workDir: File, state: State) {
        file(workDir).writeText(json.encodeToString(State.serializer(), state))
    }

    fun setScriptTag(workDir: File, scriptId: String, tag: String) {
        val s = read(workDir)
        s.scripts[scriptId] = ScriptEntry(tag)
        write(workDir, s)
        LogSink.i("ActionState", "Saved $scriptId tag=$tag")
    }

    fun getScriptTag(workDir: File, scriptId: String): String? =
        read(workDir).scripts[scriptId]?.tag

    /** Priority: update > 2036 > null (latest). */
    fun preferredModuleTag(workDir: File): String? {
        val s = read(workDir)
        return s.scripts[SCRIPT_ID_UPDATE_KAORIOS_DEX]?.tag
            ?: s.scripts[SCRIPT_ID_TOOLBOX_2036]?.tag
    }
}
