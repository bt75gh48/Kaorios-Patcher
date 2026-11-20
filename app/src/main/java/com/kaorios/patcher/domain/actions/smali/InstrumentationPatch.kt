package com.kaorios.patcher.domain.actions.smali

/**
 * Port của `patch_instrumentation` từ `kaorios_patcher.py`.
 *
 * Chèn `KaoriosHook.initContext(Context)` ngay trước MỌI `return-object` trong hai
 * overload `Instrumentation.newApplication(...)`. Riêng overload `(Class, Context)`
 * có thể là static method — khi đó Context là `p1` thay vì `p2`.
 */
object InstrumentationPatch {

    private const val HOOK =
        "Landroid/security/kaorios/KaoriosHook;->initContext(Landroid/content/Context;)V"

    private const val METHOD_CLASS_CONTEXT =
        "newApplication(Ljava/lang/Class;Landroid/content/Context;)Landroid/app/Application;"
    private const val METHOD_CLASSLOADER_CONTEXT =
        "newApplication(Ljava/lang/ClassLoader;Ljava/lang/String;Landroid/content/Context;)Landroid/app/Application;"

    private val HOOK_RE = Regex(
        "(?m)^[ \\t]*invoke-static(?:/range)?\\s*\\{p\\d+(?:\\s*\\.\\.\\s*p\\d+)?\\},\\s*" +
            Regex.escape(HOOK) + "[ \\t]*(?:#.*)?$"
    )
    private val RETURN_RE = Regex("(?m)^[ \\t]*return-object\\s+[vp]\\d+")
    private val ANY_RETURN_RE = Regex("(return-object\\s+[vp]\\d+\\s*)")
    private val ARGS_RE = Regex("\\{([^}]*)\\}")
    private val STATIC_RE = Regex("\\bstatic\\b")
    private val REGISTER_DIRECTIVE = Regex("\\.(registers|locals)\\s+(\\d+)")

    fun patch(content: String, update: Boolean = false): Pair<String, Boolean> {
        var text = content
        var changed = false

        val first = patchMethod(text, METHOD_CLASS_CONTEXT, "p2", update)
        text = first.first
        changed = changed || first.second

        val second = patchMethod(text, METHOD_CLASSLOADER_CONTEXT, "p3", update)
        text = second.first
        changed = changed || second.second

        return text to changed
    }

    private fun patchMethod(text: String, methodName: String, defaultParam: String, update: Boolean): Pair<String, Boolean> {
        val start = text.indexOf(methodName)
        require(start != -1) { "$methodName anchor method not found in Instrumentation" }
        val end = text.indexOf(".end method", start)
        require(end != -1) { "unterminated $methodName method in Instrumentation" }

        val methodBody = text.substring(start, end)
        val headerStart = text.lastIndexOf(".method", start)
        val header = text.substring(if (headerStart < 0) 0 else headerStart, start)

        val param = if ("Class;" in methodName && STATIC_RE.containsMatchIn(header)) "p1" else defaultParam

        if (HOOK_RE.containsMatchIn(methodBody)) {
            val returns = RETURN_RE.findAll(methodBody).toList()
            val hooks = HOOK_RE.findAll(methodBody).toList()
            val valid = returns.isNotEmpty() && returns.size == hooks.size && run {
                var ok = true
                for (index in returns.indices) {
                    val ret = returns[index]
                    val hook = hooks[index]
                    val args = ARGS_RE.find(hook.value)?.groupValues?.get(1)?.replace(" ", "")
                    if (args != param && args != "$param..$param") {
                        ok = false
                        break
                    }
                    if (hook.range.last + 1 > ret.range.first ||
                        methodBody.substring(hook.range.last + 1, ret.range.first).isNotBlank()
                    ) {
                        ok = false
                        break
                    }
                }
                ok
            }
            if (valid) return text to false
            require(update) { "Instrumentation: initContext must directly dominate each return" }

            val strippedBody = HOOK_RE.replace(methodBody, "")
            val clean = text.substring(0, start) + strippedBody + text.substring(end)
            val patched = patchMethod(clean, methodName, param, update = false)
            return patched.first to true
        }

        val matches = ANY_RETURN_RE.findAll(methodBody).toList()
        require(matches.isNotEmpty()) { "return-object not found in $methodName" }

        var realRegNum = 0
        REGISTER_DIRECTIVE.find(methodBody)?.let { directive ->
            val isRegisters = directive.groupValues[1] == "registers"
            val count = directive.groupValues[2].toInt()
            val paramNum = param.drop(1).toInt()
            realRegNum = if (isRegisters) {
                val paramCount = when {
                    "Class;" !in methodName -> 4
                    param == "p1" -> 2
                    else -> 3
                }
                count - paramCount + paramNum
            } else {
                count + paramNum
            }
        }

        val invoke = if (realRegNum > 15) "invoke-static/range {$param .. $param}, $HOOK"
        else "invoke-static {$param}, $HOOK"

        var newBody = methodBody
        for (match in matches.reversed()) {
            val inject = "$invoke\n\n    ${match.groupValues[1]}"
            newBody = newBody.substring(0, match.range.first) + inject + newBody.substring(match.range.last + 1)
        }
        return text.substring(0, start) + newBody + text.substring(end) to true
    }

    fun verify(content: String) {
        val body1 = KeystorePatch.extractMethodBody(content, METHOD_CLASS_CONTEXT, "Instrumentation")
        val body2 = KeystorePatch.extractMethodBody(content, METHOD_CLASSLOADER_CONTEXT, "Instrumentation")

        val returns1 = RETURN_RE.findAll(body1).count()
        val returns2 = RETURN_RE.findAll(body2).count()
        val hooks1 = HOOK_RE.findAll(body1).count()
        val hooks2 = HOOK_RE.findAll(body2).count()

        require(returns1 == 0 || hooks1 == returns1) {
            "Instrumentation: newApplication(Class,Context) expected $returns1 initContext hooks, found $hooks1"
        }
        require(returns2 == 0 || hooks2 == returns2) {
            "Instrumentation: newApplication(ClassLoader,String,Context) expected $returns2 initContext hooks, found $hooks2"
        }
        require(hooks1 != 0 || hooks2 != 0) {
            "Instrumentation: KaoriosHook initContext hook not found in either newApplication method"
        }

        val classIndex = content.indexOf(METHOD_CLASS_CONTEXT)
        val headerStart = content.lastIndexOf(".method", classIndex)
        val classHeader = content.substring(if (headerStart < 0) 0 else headerStart, classIndex)
        val classContext = if (STATIC_RE.containsMatchIn(classHeader)) "p1" else "p2"

        for ((body, contextReg) in listOf(body1 to classContext, body2 to "p3")) {
            for (ret in RETURN_RE.findAll(body)) {
                val preceding = body.substring(0, ret.range.first)
                val hook = Regex(
                    "(?m)^[ \\t]*invoke-static(?:/range)?\\s*\\{" +
                        Regex.escape(contextReg) + "(?:\\s*\\.\\.\\s*" + Regex.escape(contextReg) + ")?\\},\\s*" +
                        Regex.escape(HOOK) + "[ \\t]*(?:#.*)?\\s*\\z"
                ).find(preceding)
                require(hook != null) {
                    "Instrumentation: initContext must directly dominate each return with the Context parameter"
                }
            }
        }
    }
}
