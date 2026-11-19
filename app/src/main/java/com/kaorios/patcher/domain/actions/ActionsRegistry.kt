package com.kaorios.patcher.domain.actions

import com.kaorios.patcher.domain.actions.scripts.DexRedivisionScript
import com.kaorios.patcher.domain.actions.scripts.DisableSecureFlagScript
import com.kaorios.patcher.domain.actions.scripts.HideDevOptionsScript
import com.kaorios.patcher.domain.actions.scripts.KaoriosToolbox109Script
import com.kaorios.patcher.domain.actions.scripts.KaoriosToolbox2036Script
import com.kaorios.patcher.domain.actions.scripts.RemoveHooksScript
import com.kaorios.patcher.domain.actions.scripts.RemoveInvokeCustomScript
import com.kaorios.patcher.domain.actions.scripts.UpdateKaoriosDexScript

/**
 * Danh sách script user có thể chọn.
 *
 * Mutual exclusion:
 * - Nhóm TOOLBOX chỉ cho chọn 1 trong số 2.0.3.6 / 1.0.9.
 * - Nhóm UPDATE (UpdateKaoriosDex) cũng mutual exclusion với chính nó (1 entry).
 * - TOOLBOX và UPDATE loại trừ lẫn nhau ở tầng UI (cả 2 cùng import dex sẽ xung đột).
 * Nhóm REMOVE có thể chọn độc lập và luôn chạy đầu tiên.
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
        Entry(KaoriosToolbox109Script, ScriptGroup.TOOLBOX, ScriptPriority.TOOLBOX),
        Entry(RemoveHooksScript, ScriptGroup.REMOVE, ScriptPriority.REMOVE_HOOKS),
        Entry(RemoveInvokeCustomScript, ScriptGroup.REMOVE, ScriptPriority.REMOVE_HOOKS),
        Entry(HideDevOptionsScript, ScriptGroup.REMOVE, ScriptPriority.REMOVE_HOOKS),
        Entry(DisableSecureFlagScript, ScriptGroup.REMOVE, ScriptPriority.REMOVE_HOOKS),
        Entry(UpdateKaoriosDexScript, ScriptGroup.UPDATE, ScriptPriority.UPDATE),
        Entry(DexRedivisionScript, ScriptGroup.REBUILD, ScriptPriority.REBUILD)
    )

    /** Script chạy sau cùng — chỉ redivision, không phụ thuộc file cụ thể. */

    /** Sắp xếp danh sách selected theo priority (REMOVE_HOOKS trước). */
    fun sortForExecution(selected: List<Entry>): List<Entry> =
        selected.sortedBy { it.priority.order }
}
