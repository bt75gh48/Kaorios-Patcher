package com.kaorios.patcher.data.log

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Bộ sink log đơn giản: tất cả message của app (edit smali, build, error…) đều append vào buffer.
 * Người dùng nhấn nút "Lưu log" sẽ gọi [export] để ghi vào URI do SAF trả về.
 */
object LogSink {
    private const val TAG_ROOT = "KaoriosPatcher"
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    private val buffer = StringBuilder()
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines.asStateFlow()

    @Volatile private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        i("LogSink", "Log buffer initialised — pkg=${context.packageName}")
    }

    @Synchronized
    fun append(level: String, tag: String, msg: String, t: Throwable? = null) {
        val time = fmt.format(Date())
        val line = buildString {
            append(time).append(' ')
            append(level).append('/')
            append(tag).append(": ")
            append(msg)
            if (t != null) {
                append('\n')
                append(Log.getStackTraceString(t))
            }
        }
        buffer.append(line).append('\n')
        val snapshot = _lines.value
        val newList = if (snapshot.size > 2000) snapshot.drop(500) + line else snapshot + line
        _lines.value = newList
        when (level) {
            "E" -> Log.e("$TAG_ROOT/$tag", msg, t)
            "W" -> Log.w("$TAG_ROOT/$tag", msg, t)
            "D" -> Log.d("$TAG_ROOT/$tag", msg, t)
            else -> Log.i("$TAG_ROOT/$tag", msg, t)
        }
    }

    fun i(tag: String, msg: String) = append("I", tag, msg)
    fun d(tag: String, msg: String) = append("D", tag, msg)
    fun w(tag: String, msg: String, t: Throwable? = null) = append("W", tag, msg, t)
    fun e(tag: String, msg: String, t: Throwable? = null) = append("E", tag, msg, t)

    /**
     * Phân tầng trong buffer: dễ quét mắt (một khối = một bước lớn).
     * Dùng cho script, build, chuẩn bị workspace.
     */
    fun section(tag: String, title: String) {
        i(tag, "──────── $title ────────")
    }

    /** Dòng phụ (thụt ·) — chi tiết gọn, không trùng với [section]. */
    fun detail(tag: String, message: String) {
        i(tag, "  · $message")
    }

    fun snapshot(): String = synchronized(this) { buffer.toString() }

    suspend fun export(ctx: Context, uri: Uri) = withContext(Dispatchers.IO) {
        val data = snapshot().toByteArray(Charsets.UTF_8)
        ctx.contentResolver.openOutputStream(uri)?.use { it.write(data) }
            ?: throw IllegalStateException("ContentResolver returned null stream for $uri")
    }

    fun clear() {
        synchronized(this) {
            buffer.setLength(0)
            _lines.value = emptyList()
        }
    }
}
