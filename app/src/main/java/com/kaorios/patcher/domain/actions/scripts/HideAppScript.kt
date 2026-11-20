package com.kaorios.patcher.domain.actions.scripts

import com.kaorios.patcher.domain.actions.ActionScript
import com.kaorios.patcher.domain.actions.ScriptOp
import com.kaorios.patcher.domain.actions.smali.ComputerEnginePatch

/**
 * Script "Hide App" — chỉ chèn hook vào `ComputerEngine.shouldFilterApplication`.
 *
 * Tách khỏi `KaoriosToolbox2036Script` để chạy độc lập. Idempotent: nếu
 * `shouldFilterApplication` đã có hook, patcher trả `changed = false` thay vì lỗi.
 */
object HideAppScript : ActionScript {
    override val id = "hide_app"
    override val displayName = "Hide App"
    override val workingDirName = "hide_app"

    override val operations: List<ScriptOp> = listOf(
        ScriptOp(
            key = "services/ComputerEngine.shouldFilterApplication",
            classDescriptor = "Lcom/android/server/pm/ComputerEngine;",
            methodSignature = "",
            checkBannedWords = false,
            fileTransform = { content ->
                ComputerEnginePatch.patchVisibilityOnly(content).first
            }
        )
    )
}
