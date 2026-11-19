package com.kaorios.patcher.editor

import android.content.Context
import com.kaorios.patcher.data.log.LogSink
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import org.eclipse.tm4e.core.registry.IThemeSource

/**
 * Khởi tạo TextMate registry cho Sora editor: assets file provider + grammars (smali) + themes (light/dark).
 */
object SmaliTextMate {
    const val SCOPE_SMALI = "source.smali"
    const val THEME_DARK = "dark"
    const val THEME_LIGHT = "light"

    @Volatile private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return

            FileProviderRegistry.getInstance().addFileProvider(
                AssetsFileResolver(context.applicationContext.assets)
            )

            loadTheme(THEME_DARK, "textmate/themes/dark.json", isDark = true)
            loadTheme(THEME_LIGHT, "textmate/themes/light.json", isDark = false)
            GrammarRegistry.getInstance().loadGrammars("textmate/languages.json")

            ThemeRegistry.getInstance().setTheme(THEME_DARK)

            initialized = true
            LogSink.i("SmaliTextMate", "initialized (grammars + themes loaded)")
        }
    }

    private fun loadTheme(name: String, assetPath: String, isDark: Boolean) {
        val input = FileProviderRegistry.getInstance().tryGetInputStream(assetPath)
            ?: error("Missing theme asset: $assetPath")
        val model = ThemeModel(
            IThemeSource.fromInputStream(input, assetPath, null),
            name
        ).apply { this.isDark = isDark }
        ThemeRegistry.getInstance().loadTheme(model)
    }

    fun setTheme(isDark: Boolean) {
        ThemeRegistry.getInstance().setTheme(if (isDark) THEME_DARK else THEME_LIGHT)
    }
}
