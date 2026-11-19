package com.kaorios.patcher.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.editorDataStore by preferencesDataStore(name = "editor_prefs")

/**
 * Tùy chọn người dùng cho smali editor (port từ SettingsActivity + preferences.xml của KaoriosManager).
 */
object EditorPrefs {
    private val KEY_FONT_SIZE = floatPreferencesKey("font_size")
    private val KEY_WORD_WRAP = booleanPreferencesKey("word_wrap")
    private val KEY_LINE_NUMBER = booleanPreferencesKey("line_number")
    private val KEY_AUTO_SAVE = booleanPreferencesKey("auto_save")
    private val KEY_THEME_OVERRIDE = stringPreferencesKey("theme_override")
    private val KEY_TAB_INDENT = booleanPreferencesKey("tab_indent")

    data class Snapshot(
        val fontSize: Float = 14f,
        val wordWrap: Boolean = false,
        val lineNumber: Boolean = true,
        val autoSave: Boolean = false,
        val themeOverride: String = "system", // "system" | "dark" | "light"
        val tabIndent: Boolean = false
    )

    fun flow(ctx: Context): Flow<Snapshot> = ctx.editorDataStore.data.map { p ->
        Snapshot(
            fontSize = p[KEY_FONT_SIZE] ?: 14f,
            wordWrap = p[KEY_WORD_WRAP] ?: false,
            lineNumber = p[KEY_LINE_NUMBER] ?: true,
            autoSave = p[KEY_AUTO_SAVE] ?: false,
            themeOverride = p[KEY_THEME_OVERRIDE] ?: "system",
            tabIndent = p[KEY_TAB_INDENT] ?: false
        )
    }

    suspend fun update(ctx: Context, mutator: Snapshot.() -> Snapshot) {
        ctx.editorDataStore.edit { p ->
            val current = Snapshot(
                fontSize = p[KEY_FONT_SIZE] ?: 14f,
                wordWrap = p[KEY_WORD_WRAP] ?: false,
                lineNumber = p[KEY_LINE_NUMBER] ?: true,
                autoSave = p[KEY_AUTO_SAVE] ?: false,
                themeOverride = p[KEY_THEME_OVERRIDE] ?: "system",
                tabIndent = p[KEY_TAB_INDENT] ?: false
            )
            val next = current.mutator()
            p[KEY_FONT_SIZE] = next.fontSize
            p[KEY_WORD_WRAP] = next.wordWrap
            p[KEY_LINE_NUMBER] = next.lineNumber
            p[KEY_AUTO_SAVE] = next.autoSave
            p[KEY_THEME_OVERRIDE] = next.themeOverride
            p[KEY_TAB_INDENT] = next.tabIndent
        }
    }
}
