package com.kaorios.patcher.domain.actions.smali

import java.io.File

/**
 * Port của `verify-payload-abi.py`.
 *
 * Mọi call site `invoke-static ... Landroid/security/kaorios/KaoriosHook;->sig`
 * trong cây caller phải khớp một method `public static` (không abstract/native)
 * của facade `KaoriosHook` trong payload đã biên dịch. Đây là bước chặn cuối trước
 * khi ghi file: hook sai ABI sẽ VerifyError trên thiết bị.
 */
object PayloadAbiVerifier {

    private const val OWNER = "Landroid/security/kaorios/KaoriosHook;"

    private val METHOD = Regex("^\\.method\\s+(?<flags>.*?)\\s+(?<signature>[^\\s(]+\\([^)]*\\)\\S+)\\s*$")
    private val CALL = Regex(
        "\\binvoke-static(?:/range)?\\s+\\{[^}]*\\}\\s*,\\s*" +
            Regex.escape(OWNER) + "->([^\\s(]+\\([^)]*\\)\\S+)"
    )

    /** Trả về số call site đã kiểm tra. */
    fun verifyCalls(root: File, payloadRoot: File): Int {
        require(root.isDirectory && payloadRoot.isDirectory) {
            "caller and payload smali roots must be directories"
        }

        val facades = payloadRoot.walkTopDown()
            .filter { it.isFile && it.name == "KaoriosHook.smali" }
            .map { it to instructions(it) }
            .filter { (_, lines) ->
                lines.any { it.startsWith(".class ") && it.split(Regex("\\s+")).last() == OWNER }
            }
            .toList()
        require(facades.size == 1) { "expected exactly one payload facade; found ${facades.size}" }

        val methods = mutableMapOf<String, Set<String>>()
        for (line in facades[0].second) {
            val match = METHOD.matchEntire(line) ?: continue
            val signature = match.groups["signature"]!!.value
            require(signature !in methods) { "duplicate payload method: $signature" }
            methods[signature] = match.groups["flags"]!!.value.split(Regex("\\s+")).toSet()
        }

        val facadePath = facades[0].first.canonicalFile
        var count = 0
        root.walkTopDown()
            .filter { it.isFile && it.extension == "smali" }
            .forEach { file ->
                if (file.canonicalFile == facadePath) return@forEach
                for (line in instructions(file)) {
                    for (match in CALL.findAll(line)) {
                        val signature = match.groupValues[1]
                        val flags = methods[signature] ?: emptySet()
                        require("public" in flags && "static" in flags &&
                            "abstract" !in flags && "native" !in flags
                        ) { "${file.path}: supplied payload lacks public static $signature" }
                        count++
                    }
                }
            }
        return count
    }

    /** Chuỗi và comment là data, không bao giờ là định nghĩa method hay call. */
    private fun instructions(file: File): Sequence<String> =
        file.readText(Charsets.UTF_8).lineSequence()
            .map { SmaliOps.stripData(it).trim() }
}
