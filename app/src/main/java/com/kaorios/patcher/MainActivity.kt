package com.kaorios.patcher

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.kaorios.patcher.domain.model.WorkMode
import com.kaorios.patcher.ui.navigation.Routes
import com.kaorios.patcher.ui.screen.browser.FileBrowserScreen
import com.kaorios.patcher.ui.screen.editor.JavaViewScreen
import com.kaorios.patcher.ui.screen.editor.SmaliEditorScreen
import com.kaorios.patcher.ui.screen.log.LogViewerScreen
import com.kaorios.patcher.ui.screen.mode.ModeSelectScreen
import com.kaorios.patcher.ui.screen.picker.JarPickerScreen
import com.kaorios.patcher.ui.screen.settings.SettingsScreen
import com.kaorios.patcher.ui.theme.KaoriosPatcherTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KaoriosPatcherTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    AppRoot()
                }
            }
        }
    }
}

@Composable
private fun AppRoot() {
    val nav = rememberNavController()
    Scaffold(modifier = Modifier.fillMaxSize()) { _ ->
        NavHost(navController = nav, startDestination = Routes.MODE_SELECT) {
            composable(Routes.MODE_SELECT) {
                ModeSelectScreen(
                    onPickMode = { nav.navigate(Routes.jarPicker(it)) },
                    onOpenLog = { nav.navigate(Routes.LOG) },
                    onOpenSettings = { nav.navigate(Routes.SETTINGS) }
                )
            }
            composable(
                Routes.JAR_PICKER,
                arguments = listOf(navArgument("mode") { type = NavType.StringType })
            ) { entry ->
                val mode = WorkMode.valueOf(entry.arguments?.getString("mode") ?: WorkMode.KAORIOS_PATCHER.name)
                JarPickerScreen(
                    mode = mode,
                    onBack = { nav.popBackStack() },
                    onReady = { workDir ->
                        nav.navigate(Routes.browser(workDir)) {
                            popUpTo(Routes.MODE_SELECT) { inclusive = false }
                        }
                    }
                )
            }
            composable(
                Routes.BROWSER,
                arguments = listOf(navArgument("workDir") { type = NavType.StringType })
            ) { entry ->
                val workDir = Routes.decode(entry.arguments?.getString("workDir"))
                FileBrowserScreen(
                    workDir = workDir,
                    onBack = { nav.popBackStack() },
                    onOpenFile = { path -> nav.navigate(Routes.editor(workDir, path)) },
                    onOpenLog = { nav.navigate(Routes.LOG) }
                )
            }
            composable(
                Routes.EDITOR,
                arguments = listOf(
                    navArgument("workDir") { type = NavType.StringType },
                    navArgument("filePath") { type = NavType.StringType }
                )
            ) { entry ->
                val workDir = Routes.decode(entry.arguments?.getString("workDir"))
                val filePath = Routes.decode(entry.arguments?.getString("filePath"))
                SmaliEditorScreen(
                    workDir = workDir,
                    filePath = filePath,
                    onBack = { nav.popBackStack() },
                    onOpenJavaView = { nav.navigate(Routes.javaView(it)) },
                    onOpenSettings = { nav.navigate(Routes.SETTINGS) }
                )
            }
            composable(
                Routes.JAVA_VIEW,
                arguments = listOf(navArgument("filePath") { type = NavType.StringType })
            ) { entry ->
                val filePath = Routes.decode(entry.arguments?.getString("filePath"))
                JavaViewScreen(filePath = filePath, onBack = { nav.popBackStack() })
            }
            composable(Routes.LOG) {
                LogViewerScreen(onBack = { nav.popBackStack() })
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(onBack = { nav.popBackStack() })
            }
        }
    }
}
