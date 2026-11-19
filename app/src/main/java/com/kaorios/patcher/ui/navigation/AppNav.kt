package com.kaorios.patcher.ui.navigation

import android.net.Uri
import androidx.navigation.NavController
import com.kaorios.patcher.domain.model.WorkMode
import java.net.URLDecoder
import java.net.URLEncoder

object Routes {
    const val MODE_SELECT = "modeSelect"
    const val JAR_PICKER = "jarPicker/{mode}"
    fun jarPicker(mode: WorkMode) = "jarPicker/${mode.name}"

    const val BROWSER = "browser/{workDir}"
    fun browser(workDir: String) = "browser/${encode(workDir)}"

    const val EDITOR = "editor/{workDir}/{filePath}"
    fun editor(workDir: String, filePath: String) =
        "editor/${encode(workDir)}/${encode(filePath)}"

    const val LOG = "log"
    const val SETTINGS = "settings"
    const val JAVA_VIEW = "javaView/{filePath}"
    fun javaView(filePath: String) = "javaView/${encode(filePath)}"

    fun encode(s: String): String = URLEncoder.encode(s, "UTF-8")
    fun decode(s: String?): String = if (s == null) "" else URLDecoder.decode(s, "UTF-8")
}

fun NavController.safeNavigate(route: String) {
    try {
        navigate(route)
    } catch (_: Throwable) {
    }
}

fun String?.toUriOrNull(): Uri? = this?.let { runCatching { Uri.parse(it) }.getOrNull() }
