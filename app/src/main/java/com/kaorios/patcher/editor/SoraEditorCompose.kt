package com.kaorios.patcher.editor

import android.content.Context
import android.graphics.Typeface
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.kaorios.patcher.data.settings.EditorPrefs
import io.github.rosemoe.sora.event.ContentChangeEvent
import io.github.rosemoe.sora.langs.textmate.TextMateColorScheme
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.widget.CodeEditor

class SoraEditorState {
    var editor: CodeEditor? = null
}

@Composable
fun rememberSoraEditorState(): SoraEditorState = remember { SoraEditorState() }

/**
 * Compose wrapper cho Sora [CodeEditor].
 *
 * Quản lý re-setText an toàn:
 * - `lastAppliedText` là giá trị **prop `text`** gần nhất mà composable đã xử lý.
 * - Chỉ gọi `editor.setText(text)` khi prop `text` mới **khác** giá trị lần cuối.
 * - Việc user gõ sẽ đi qua vòng: editor → event → onTextChanged → VM state → prop đổi.
 *   Lúc prop loop-back về, editor đã có nội dung đó rồi nên ta chỉ cập nhật `lastAppliedText`,
 *   KHÔNG setText lại (setText sẽ reset cursor + scroll → "màn hình nhảy").
 * - Khi load file mới thật sự, prop đổi sang giá trị chưa từng thấy → setText như bình thường.
 */
@Composable
fun SoraSmaliEditor(
    modifier: Modifier = Modifier,
    state: SoraEditorState,
    text: String,
    readOnly: Boolean = false,
    prefs: EditorPrefs.Snapshot = EditorPrefs.Snapshot(),
    onTextChanged: (String) -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val useDark = when (prefs.themeOverride) {
        "dark" -> true
        "light" -> false
        else -> systemDark
    }
    LaunchedEffect(useDark) { SmaliTextMate.setTheme(useDark) }

    // Giá trị prop text đã được apply vào editor. Nếu prop sắp tới bằng giá trị này,
    // nghĩa là đây là loop-back từ chính user edit → không setText lại.
    val lastAppliedText = remember { mutableStateOf(text) }

    AndroidView(
        modifier = modifier,
        factory = { ctx -> createEditor(ctx, state, text, lastAppliedText, onTextChanged) },
        update = { editor ->
            if (text != lastAppliedText.value) {
                // Prop đổi giá trị — có thể là external load (file mới) hoặc loop-back sau user edit.
                // Chỉ setText nếu editor chưa có nội dung đó (tức external load).
                if (editor.text.toString() != text) {
                    editor.setText(text)
                }
                lastAppliedText.value = text
            }
            editor.isEditable = !readOnly
            editor.setTextSize(prefs.fontSize)
            editor.isWordwrap = prefs.wordWrap
            editor.isLineNumberEnabled = prefs.lineNumber
            editor.tabWidth = 4
        }
    )

    DisposableEffect(Unit) {
        onDispose {
            state.editor?.release()
            state.editor = null
        }
    }
}

private fun createEditor(
    ctx: Context,
    state: SoraEditorState,
    initialText: String,
    lastAppliedText: androidx.compose.runtime.MutableState<String>,
    onChanged: (String) -> Unit
): CodeEditor {
    val editor = CodeEditor(ctx)
    state.editor = editor
    editor.typefaceText = Typeface.MONOSPACE
    editor.setTextSize(14f)
    editor.colorScheme = TextMateColorScheme.create(ThemeRegistry.getInstance())
    editor.setEditorLanguage(TextMateLanguage.create(SmaliTextMate.SCOPE_SMALI, true))
    editor.nonPrintablePaintingFlags = CodeEditor.FLAG_DRAW_LINE_SEPARATOR
    editor.setText(initialText)
    lastAppliedText.value = initialText

    editor.subscribeEvent(ContentChangeEvent::class.java) { event, _ ->
        val newText = event.editor.text.toString()
        if (newText != lastAppliedText.value) {
            // Đồng bộ ngay để update-block sắp chạy không gọi setText lại.
            lastAppliedText.value = newText
            onChanged(newText)
        }
        // Đảm bảo cursor luôn trong vùng nhìn sau mỗi edit (gõ / paste / xoá / undo).
        // Nếu người dùng cuộn xa rồi gõ, editor sẽ auto scroll về dòng đang gõ.
        val cur = event.editor.cursor
        event.editor.post {
            runCatching { event.editor.ensurePositionVisible(cur.leftLine, cur.leftColumn) }
        }
    }
    return editor
}
