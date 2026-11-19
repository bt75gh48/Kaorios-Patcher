package com.kaorios.patcher.domain.actions.scripts

import android.content.Context
import com.kaorios.patcher.data.log.LogSink
import com.kaorios.patcher.domain.actions.ActionScript
import com.kaorios.patcher.domain.actions.ScriptOp
import com.kaorios.patcher.domain.actions.SmaliEngine
import java.io.File

/**
 * Script "Remove invoke-custom" — loại bỏ invoke-custom bằng cách clear body của các method
 * equals, hashCode, toString trong các class được chỉ định, trả về giá trị false mặc định.
 *
 * Các class cần xử lý:
 * - Framework classes (inner classes)
 * - Services classes (inner classes)
 *
 * Với mỗi class, kiểm tra body method có từ cấm → bỏ qua (clear = false).
 */
object RemoveInvokeCustomScript : ActionScript {
    override val id = "remove_invoke_custom"
    override val displayName = "Remove invoke-custom"
    override val workingDirName = "remove_invoke_custom"

    /** Framework classes chứa các inner class với invoke-custom. */
    private val FRAMEWORK_CLASSES = listOf(
        "Landroid/hardware/input/KeyboardLayoutPreviewDrawable\$GlyphDrawable;",
        "Landroid/hardware/input/PhysicalKeyLayout\$EnterKey;",
        "Landroid/hardware/input/PhysicalKeyLayout\$LayoutKey;",
        "Landroid/media/MediaRouter2\$InstanceInvalidatedCallbackRecord;",
        "Landroid/media/MediaRouter2\$PackageNameUserHandlePair;"
    )

    /** Services classes chứa các inner class với invoke-custom. */
    private val SERVICES_CLASSES = listOf(
        "Lcom/android/server/BinaryTransparencyService\$Digest;",
        "Lcom/android/server/inputmethod/AdditionalSubtypeMapRepository\$WriteTask;",
        "Lcom/android/server/policy/PhoneWindowManager\$SwitchKeyboardLayoutMessageObject;",
        "Lcom/android/server/policy/PhoneWindowManager\$SwitchKeyboardLayoutMessageObject;",
        "Lcom/android/server/policy/SingleKeyGestureDetector\$MessageObject;"
    )

    /** Các method cần clear trong mỗi class. */
    private val METHODS_TO_CLEAR = listOf(
        "equals(Ljava/lang/Object;)Z",
        "hashCode()I",
        "toString()Ljava/lang/String;"
    )

    override val operations: List<ScriptOp> = buildList {
        // Thêm operations cho framework classes
        for (classDesc in FRAMEWORK_CLASSES) {
            for (methodSig in METHODS_TO_CLEAR) {
                add(createClearMethodOp(classDesc, methodSig))
            }
        }
        // Thêm operations cho services classes
        for (classDesc in SERVICES_CLASSES) {
            for (methodSig in METHODS_TO_CLEAR) {
                add(createClearMethodOp(classDesc, methodSig))
            }
        }
    }

    private fun createClearMethodOp(classDesc: String, methodSig: String): ScriptOp {
        return ScriptOp(
            key = "${classDesc.removeSuffix(";").replace("/", ".")}.$methodSig",
            classDescriptor = classDesc,
            methodSignature = methodSig,
            checkBannedWords = true
        ) { m ->
            clearMethodToReturnFalse(m)
        }
    }

    /**
     * Clear method body để trả về giá trị false mặc định:
     * - equals: return false (const/4 v0, 0x0; return v0)
     * - hashCode: return 0 (const/4 v0, 0x0; return v0)
     * - toString: return null (const/4 v0, 0x0; return-object v0)
     *
     * Nếu body method KHÔNG chứa lệnh `invoke-custom` → bỏ qua (idempotent, tránh ghi đè
     * những method equals/hashCode/toString hợp lệ không cần thay).
     */
    private fun clearMethodToReturnFalse(method: SmaliEngine.MutableMethod) {
        if (method.body.none { it.trim().startsWith("invoke-custom") }) return

        val sig = SmaliEngine.methodSignature(method.header)

        val newBody = when {
            sig.startsWith("equals") -> buildEqualsBody()
            sig.startsWith("hashCode") -> buildHashCodeBody()
            sig.startsWith("toString") -> buildToStringBody()
            else -> return
        }

        method.body.clear()
        method.body.addAll(newBody)
    }

    private fun buildEqualsBody(): List<String> = listOf(
        "    .registers 2",
        "",
        "    const/4 v0, 0x0",
        "",
        "    return v0"
    )

    private fun buildHashCodeBody(): List<String> = listOf(
        "    .registers 1",
        "",
        "    const/4 v0, 0x0",
        "",
        "    return v0"
    )

    private fun buildToStringBody(): List<String> = listOf(
        "    .registers 1",
        "",
        "    const/4 v0, 0x0",
        "",
        "    return-object v0"
    )
}
