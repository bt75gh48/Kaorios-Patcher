package com.kaorios.patcher.domain.actions

import com.kaorios.patcher.domain.actions.scripts.DexRedivisionScript
import com.kaorios.patcher.domain.actions.scripts.DisableSecureFlagScript
import com.kaorios.patcher.domain.actions.scripts.HideAppScript
import com.kaorios.patcher.domain.actions.scripts.HideDevOptionsScript
import com.kaorios.patcher.domain.actions.scripts.KaoriosToolbox2036Script
import com.kaorios.patcher.domain.actions.scripts.RemoveBuildFlagScript
import com.kaorios.patcher.domain.actions.scripts.RemoveHooksScript
import com.kaorios.patcher.domain.actions.scripts.RemoveInvokeCustomScript
import com.kaorios.patcher.domain.actions.scripts.SpoofInstalledSourceScript
import com.kaorios.patcher.domain.actions.scripts.UpdateKaoriosDexScript

/**
 * Danh sách script user có thể chọn, theo đúng thứ tự hiển thị và thực thi.
 *
 * Mutual exclusion: chỉ `UpdateKaoriosDexScript` và `KaoriosToolbox2036Script` cùng
 * import dex nên loại trừ nhau ở tầng UI.
 */
object ActionsRegistry {

    data class Entry(
        val script: ActionScript,
        val group: ScriptGroup,
        val priority: ScriptPriority,
        val isExperimental: Boolean = false
    )

    val all: List<Entry> = listOf(
        Entry(KaoriosToolbox2036Script, ScriptGroup.TOOLBOX, ScriptPriority.TOOLBOX),
        Entry(RemoveHooksScript, ScriptGroup.REMOVE, ScriptPriority.REMOVE_HOOKS),
        Entry(RemoveInvokeCustomScript, ScriptGroup.REMOVE, ScriptPriority.REMOVE_INVOKE_CUSTOM),
        Entry(RemoveBuildFlagScript, ScriptGroup.REMOVE, ScriptPriority.REMOVE_BUILD_FLAG),
        Entry(DexRedivisionScript, ScriptGroup.REBUILD, ScriptPriority.REBUILD),
        Entry(UpdateKaoriosDexScript, ScriptGroup.UPDATE, ScriptPriority.UPDATE),
        Entry(HideDevOptionsScript, ScriptGroup.REMOVE, ScriptPriority.HIDE_DEV_OPTIONS),
        Entry(HideAppScript, ScriptGroup.REMOVE, ScriptPriority.HIDE_APP),
        Entry(SpoofInstalledSourceScript, ScriptGroup.REMOVE, ScriptPriority.SPOOF_INSTALLED_SOURCE),
        Entry(DisableSecureFlagScript, ScriptGroup.REMOVE, ScriptPriority.DISABLE_SECURE_FLAG)
    )

    /** Sắp xếp danh sách selected theo priority, giữ thứ tự khai báo trong cùng priority. */
    fun sortForExecution(selected: List<Entry>): List<Entry> =
        selected.sortedBy { it.priority.order }
}
