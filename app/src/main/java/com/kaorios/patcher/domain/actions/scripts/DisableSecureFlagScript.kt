package com.kaorios.patcher.domain.actions.scripts

import com.kaorios.patcher.domain.actions.ActionScript
import com.kaorios.patcher.domain.actions.ScriptOp
import com.kaorios.patcher.domain.actions.SmaliEngine

/**
 * Script "Disable SecureFlag" — chèn hook `Landroid/security/kaorios/KaoriosHook;->isSecureFlag()Z`
 * vào 4 method bên services để cho phép screen capture trên các cửa sổ secure.
 *
 * Yêu cầu: framework + services đã được vá dex KaoriosToolbox 2.0.3+ (chứa class `KaoriosHook`).
 *
 * - `DevicePolicyCacheImpl.isScreenCaptureAllowed`: trả về true khi isSecureFlag()=true.
 * - `WindowManagerService.captureDisplay`: sau check `notAllowCaptureDisplay()` (nếu có), override
 *   kết quả về 0 khi isSecureFlag()=true; ROM không có block đó → bỏ qua op này, script vẫn OK.
 * - `WindowState.isSecureLocked`: trả về false khi isSecureFlag()=true.
 * - `WindowState.setSecureLocked`: return sớm (void) khi isSecureFlag()=true, bỏ qua gán secure.
 */
object DisableSecureFlagScript : ActionScript {
    override val id = "disable_secure_flag"
    override val displayName = "Disable SecureFlag"
    override val workingDirName = "disable_secure_flag"

    override val operations: List<ScriptOp> = listOf(
        // Op 1: DevicePolicyCacheImpl.isScreenCaptureAllowed(I)Z — chèn sau .registers, return true nếu isSecureFlag.
        // Không check banned: ROM gốc đã có thể tham chiếu tới `kaorios` (đã vá Toolbox trước đó).
        ScriptOp(
            key = "services/DevicePolicyCacheImpl.isScreenCaptureAllowed",
            classDescriptor = "Lcom/android/server/devicepolicy/DevicePolicyCacheImpl;",
            methodSignature = "isScreenCaptureAllowed(I)Z",
            checkBannedWords = false
        ) { m ->
            val block = """
                invoke-static {}, Landroid/security/kaorios/KaoriosHook;->isSecureFlag()Z

                move-result v0

                if-eqz v0, :cond_kaorios

                const/4 v0, 0x1

                return v0

                :cond_kaorios
            """.trimIndent()
            SmaliEngine.insertAfterRegisters(m, block)
        },

        // Op 2: WindowManagerService.captureDisplay — optional: bỏ qua nếu ROM không có notAllowCaptureDisplay.
        ScriptOp(
            key = "services/WindowManagerService.captureDisplay",
            classDescriptor = "Lcom/android/server/wm/WindowManagerService;",
            methodSignature = "captureDisplay(ILandroid/window/ScreenCapture\$CaptureArgs;Landroid/window/ScreenCapture\$ScreenCaptureListener;)V",
            checkBannedWords = false
        ) { m ->
            SmaliEngine.tryInsertAfterMoveResultFollowingLine(
                m,
                anchorContains = "Lcom/android/server/wm/WindowManagerServiceStub;->notAllowCaptureDisplay(Lcom/android/server/wm/RootWindowContainer;I)Z"
            ) { movedReg, newReg ->
                """
                invoke-static {}, Landroid/security/kaorios/KaoriosHook;->isSecureFlag()Z

                move-result $newReg

                if-eqz $newReg, :cond_kaorios

                const/4 $movedReg, 0x0

                :cond_kaorios
                """.trimIndent()
            }
        },

        // Op 3: WindowState.isSecureLocked()Z — chèn sau .registers, return false nếu isSecureFlag.
        ScriptOp(
            key = "services/WindowState.isSecureLocked",
            classDescriptor = "Lcom/android/server/wm/WindowState;",
            methodSignature = "isSecureLocked()Z",
            checkBannedWords = false
        ) { m ->
            val block = """
                invoke-static {}, Landroid/security/kaorios/KaoriosHook;->isSecureFlag()Z

                move-result v0

                if-eqz v0, :cond_kaorios

                const/4 v0, 0x0

                return v0

                :cond_kaorios
            """.trimIndent()
            SmaliEngine.insertAfterRegisters(m, block)
        },

        // Op 4: WindowState.setSecureLocked(Z)V — return-void sớm khi isSecureFlag, không gán secure.
        ScriptOp(
            key = "services/WindowState.setSecureLocked",
            classDescriptor = "Lcom/android/server/wm/WindowState;",
            methodSignature = "setSecureLocked(Z)V",
            checkBannedWords = false
        ) { m ->
            val block = """
                invoke-static {}, Landroid/security/kaorios/KaoriosHook;->isSecureFlag()Z

                move-result v0

                if-eqz v0, :cond_kaorios

                return-void

                :cond_kaorios
            """.trimIndent()
            SmaliEngine.insertAfterRegisters(m, block)
        }
    )
}
