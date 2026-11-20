package com.kaorios.patcher.domain.actions.smali

/**
 * Port của `patch-systemserver-a17.py`.
 *
 * Chèn `KaoriosHook.initSystemServer()` ngay trước `Looper.loop()` trong
 * `SystemServer.run()V`. Anchor phải xuất hiện đúng một lần, hook phải đứng
 * trước anchor và không có `.end method` chen giữa.
 */
object SystemServerPatch {

    private const val HOOK_TARGET = "Landroid/security/kaorios/KaoriosHook;->initSystemServer()V"
    private const val HOOK_CALL = "invoke-static {}, Landroid/security/kaorios/KaoriosHook;->initSystemServer()V"
    private const val ANCHOR_CALL = "invoke-static {}, Landroid/os/Looper;->loop()V"

    private val CLASS_RE = Regex("(?m)^\\.class\\s+.*Lcom/android/server/SystemServer;\\s*$")
    private val METHOD_RUN_RE = Regex("(?m)^\\.method\\s+(?:public\\s+|private\\s+|protected\\s+)?(?:final\\s+)?run\\(\\)V\\s*$")
    private val METHOD_END_RE = Regex("(?m)^[ \\t]*\\.end method[ \\t]*(?:\\r?\\n|$)")
    private val HOOK_RE = Regex(Regex.escape(HOOK_TARGET))

    private fun methodSpan(text: String): Pair<Int, Int> {
        require(CLASS_RE.containsMatchIn(text)) { "expected .class Lcom/android/server/SystemServer;" }

        val matches = METHOD_RUN_RE.findAll(text).toList()
        require(matches.isNotEmpty()) { "target SystemServer.run()V method not found" }
        require(matches.size == 1) { "ambiguous SystemServer.run()V: found ${matches.size} matches" }

        val end = METHOD_END_RE.find(text, matches[0].range.last + 1)
            ?: error("unterminated SystemServer.run()V method")
        return matches[0].range.first to end.range.last + 1
    }

    fun verify(text: String) {
        val (start, end) = methodSpan(text)
        val body = text.substring(start, end)

        val total = HOOK_RE.findAll(text).count()
        require(total == 1) { "expected exactly one initSystemServer hook in class; found $total" }
        require(HOOK_CALL in body) {
            "expected exact invoke-static {}, Landroid/security/kaorios/KaoriosHook;->initSystemServer()V in run()V"
        }
        require(ANCHOR_CALL in body) { "expected Looper.loop() in SystemServer.run()V" }

        // Hook phải đứng trước Looper.loop() và không vượt qua .end method.
        val hookIndex = body.indexOf(HOOK_CALL)
        val anchorIndex = body.indexOf(ANCHOR_CALL)
        require(hookIndex < anchorIndex) { "hook must precede Looper.loop() in SystemServer.run()V" }
        require(".end method" !in body.substring(hookIndex, anchorIndex)) {
            "hook must precede Looper.loop() in SystemServer.run()V"
        }
    }

    fun patch(text: String): Pair<String, Boolean> {
        val (start, end) = methodSpan(text)
        val body = text.substring(start, end)

        if (HOOK_TARGET in body) {
            verify(text)
            return text to false
        }

        val anchorMatches = Regex(
            "(?m)^(?<indent>[ \\t]*)" + Regex.escape(ANCHOR_CALL) + "[ \\t]*(?:\\r?\\n|$)"
        ).findAll(body).toList()
        require(anchorMatches.isNotEmpty()) {
            "anchor 'invoke-static {}, Landroid/os/Looper;->loop()V' not found in SystemServer.run()V"
        }
        require(anchorMatches.size == 1) {
            "ambiguous anchor: found ${anchorMatches.size} Looper.loop() in SystemServer.run()V"
        }

        val match = anchorMatches[0]
        val indent = match.groups["indent"]!!.value
        val injection = "$indent$HOOK_CALL\n\n"
        val newBody = body.substring(0, match.range.first) + injection + body.substring(match.range.first)
        val newText = text.substring(0, start) + newBody + text.substring(end)

        verify(newText)
        return newText to true
    }
}
