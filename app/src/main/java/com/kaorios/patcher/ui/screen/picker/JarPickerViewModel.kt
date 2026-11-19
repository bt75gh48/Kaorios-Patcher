package com.kaorios.patcher.ui.screen.picker

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kaorios.patcher.R
import com.kaorios.patcher.data.framework.SystemFrameworkReader
import com.kaorios.patcher.data.log.LogSink
import com.kaorios.patcher.domain.model.WorkMode
import com.kaorios.patcher.domain.usecase.PrepareWorkspaceUseCase
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class JarPickerViewModel(app: Application) : AndroidViewModel(app) {

    data class JarEntry(
        val source: PrepareWorkspaceUseCase.Input.Source? = null,
        val displayName: String,
        val logicalName: String
    )

    data class UiState(
        val mode: WorkMode = WorkMode.KAORIOS_PATCHER,
        val entries: List<JarEntry> = emptyList(),
        val running: Boolean = false,
        val progressStage: String = "",
        val progressCurrent: Int = 0,
        val progressTotal: Int = 0,
        val error: String? = null,
        val workDir: String? = null,
        val systemFrameworkAvailable: Boolean = false
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var buildJob: Job? = null

    /**
     * Cancel build nếu đang chạy.
     * Gọi khi người dùng nhấn back hoặc rời màn hình.
     */
    fun cancelBuild() {
        buildJob?.cancel()
        buildJob = null
        _state.value = _state.value.copy(running = false, error = null)
        LogSink.d("JarPicker", "Build cancelled by user")
    }

    override fun onCleared() {
        super.onCleared()
        cancelBuild()
    }

    fun setMode(mode: WorkMode) {
        val app = getApplication<Application>()
        val defaults = when (mode) {
            WorkMode.KAORIOS_PATCHER -> listOf(
                JarEntry(null, app.getString(R.string.picker_placeholder_framework), "framework"),
                JarEntry(null, app.getString(R.string.picker_placeholder_services), "services")
            )
            WorkMode.OTHER_JAR -> listOf(JarEntry(null, app.getString(R.string.picker_placeholder_jar), "input"))
        }
        _state.value = UiState(
            mode = mode,
            entries = defaults,
            systemFrameworkAvailable = SystemFrameworkReader.isAvailable()
        )
    }

    fun onSafPicked(index: Int, uri: Uri, displayName: String) {
        updateEntry(index) { entry ->
            val logical = if (_state.value.mode == WorkMode.KAORIOS_PATCHER) entry.logicalName
            else sanitizeName(displayName)
            JarEntry(
                source = PrepareWorkspaceUseCase.Input.Source.Saf(uri),
                displayName = displayName,
                logicalName = logical
            )
        }
    }

    fun onSystemPicked(index: Int, filePath: String, displayName: String) {
        updateEntry(index) { entry ->
            val logical = if (_state.value.mode == WorkMode.KAORIOS_PATCHER) entry.logicalName
            else sanitizeName(displayName)
            JarEntry(
                source = PrepareWorkspaceUseCase.Input.Source.FilePath(filePath),
                displayName = getApplication<Application>().getString(
                    R.string.picker_display_system_fmt,
                    displayName
                ),
                logicalName = logical
            )
        }
    }

    private fun updateEntry(index: Int, mutator: (JarEntry) -> JarEntry) {
        val current = _state.value
        val updated = current.entries.toMutableList().apply {
            this[index] = mutator(this[index])
        }
        _state.value = current.copy(entries = updated, error = null)
    }

    fun listSystemFramework(): List<SystemFrameworkReader.Entry> = SystemFrameworkReader.listJars()

    fun start(onDone: (workDir: String) -> Unit) {
        val s = _state.value
        if (s.entries.any { it.source == null }) {
            _state.value = s.copy(error = getApplication<Application>().getString(R.string.picker_fill_all))
            return
        }
        if (s.running) return
        _state.value = s.copy(running = true, error = null, progressCurrent = 0, progressTotal = s.entries.size)

        buildJob = viewModelScope.launch {
            runCatching {
                val input = PrepareWorkspaceUseCase.Input(
                    mode = s.mode,
                    inputs = s.entries.map {
                        PrepareWorkspaceUseCase.Input.JarInput(
                            logicalName = it.logicalName,
                            displayName = it.displayName,
                            source = it.source!!
                        )
                    }
                )
                PrepareWorkspaceUseCase(getApplication()).invoke(input) { stage, cur, total ->
                    _state.value = _state.value.copy(
                        progressStage = stage,
                        progressCurrent = cur,
                        progressTotal = total
                    )
                }
            }.onSuccess { session ->
                _state.value = _state.value.copy(running = false, workDir = session.workDir)
                onDone(session.workDir)
            }.onFailure { t ->
                LogSink.e("JarPicker", "Prepare workspace failed", t)
                _state.value = _state.value.copy(running = false, error = t.message ?: t.toString())
            }
        }
    }

    private fun sanitizeName(displayName: String): String {
        val base = displayName.substringBeforeLast('.').ifEmpty { displayName }
        return base.replace(Regex("[^A-Za-z0-9_]"), "_").ifEmpty { "input" }
    }
}
