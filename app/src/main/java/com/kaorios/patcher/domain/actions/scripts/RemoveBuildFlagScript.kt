package com.kaorios.patcher.domain.actions.scripts

import com.kaorios.patcher.domain.actions.ActionScript
import com.kaorios.patcher.domain.actions.PatchMode
import com.kaorios.patcher.domain.actions.ScriptOp
import com.kaorios.patcher.domain.actions.SmaliEngine

/**
 * Script "Remove Build Flag (Android 17)" — ghi lại các Build field chỉ có trên
 * Android 17 (`Build` + `Build$VERSION`).
 *
 * Tách ra từ `HideDevOptionsScript`. Chỉ chạy ở mode 2/3 vì các field đích không
 * tồn tại trên Android 13–16.
 */
object RemoveBuildFlagScript : ActionScript {
    override val id = "remove_build_flag"
    override val displayName = "Remove Build Flag (Android 17)"
    override val workingDirName = "remove_build_flag"

    override val operations: List<ScriptOp> = listOf(
        ScriptOp(
            key = "framework/Build.fields",
            classDescriptor = "Landroid/os/Build;",
            methodSignature = "<clinit>()V",
            checkBannedWords = false,
            patchMode = PatchMode.BUILD,
            fileTransform = { content ->
                val patched = SmaliEngine.patchBuildFields(content)
                SmaliEngine.verifyBuildFields(patched)
                patched
            }
        ) { },

        ScriptOp(
            key = "framework/Build.VERSION.fields",
            classDescriptor = "Landroid/os/Build\$VERSION;",
            methodSignature = "<clinit>()V",
            checkBannedWords = false,
            patchMode = PatchMode.BUILD,
            fileTransform = { content ->
                val patched = SmaliEngine.patchBuildVersionFields(content)
                SmaliEngine.verifyBuildVersionFields(patched)
                patched
            }
        ) { }
    )
}
