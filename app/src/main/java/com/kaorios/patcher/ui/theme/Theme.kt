package com.kaorios.patcher.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import com.kaorios.patcher.data.settings.EditorPrefs

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80
)

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40
)

/**
 * Material 3 theme reactive theo [EditorPrefs.themeOverride].
 *
 * - `"system"`: theo trạng thái dark của hệ thống.
 * - `"dark"`: luôn dark.
 * - `"light"`: luôn light.
 *
 * Khi user đổi trong SettingsScreen, DataStore flow emit → composable recompose →
 * `darkTheme` cập nhật → Material recolor toàn bộ UI. Đồng thời `SmaliTextMate` đọc
 * cùng prefs trong editor để đổi theme sora.
 */
@Composable
fun KaoriosPatcherTheme(
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val ctx = LocalContext.current
    val prefs by EditorPrefs.flow(ctx).collectAsState(initial = EditorPrefs.Snapshot())
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (prefs.themeOverride) {
        "dark" -> true
        "light" -> false
        else -> systemDark
    }
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
