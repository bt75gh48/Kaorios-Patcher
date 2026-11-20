package com.kaorios.patcher.domain.actions.scripts

import com.kaorios.patcher.domain.actions.ActionScript
import com.kaorios.patcher.domain.actions.ScriptOp
import com.kaorios.patcher.domain.actions.smali.ComputerEnginePatch

/**
 * Script "Spoof Installed Source" — chèn hook vào `getInstallerPackageName` /
 * `getInstallSourceInfo` của `ComputerEngine` để trả về nguồn cài đặt giả.
 *
 * Tách khỏi `KaoriosToolbox2036Script` để chạy độc lập. Fail-closed nếu
 * `ComputerEngine` không có cả hai installer API.
 */
object SpoofInstalledSourceScript : ActionScript {
    override val id = "spoof_installed_source"
    override val displayName = "Spoof Installed Source"
    override val workingDirName = "spoof_installed_source"

    override val operations: List<ScriptOp> = listOf(
        ScriptOp(
            key = "services/ComputerEngine.installerSource",
            classDescriptor = "Lcom/android/server/pm/ComputerEngine;",
            methodSignature = "",
            checkBannedWords = false,
            fileTransform = { content ->
                ComputerEnginePatch.patchInstallerOnly(content).first
            }
        )
    )
}
