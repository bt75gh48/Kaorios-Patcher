package com.kaorios.patcher.domain.actions.scripts

import com.kaorios.patcher.domain.actions.ActionScript
import com.kaorios.patcher.domain.actions.ScriptOp
import com.kaorios.patcher.domain.actions.SmaliEngine

/**
 * Script "HideDevOptions" — ẩn Developer Options bằng cách chèn hook check vào các method
 * Settings của framework.
 *
 * Hook gọi `Landroid/security/kaorios/KaoriosHook;->shouldHideDevStatus()` để kiểm tra
 * xem có nên ẩn developer options hay không.
 */
object HideDevOptionsScript : ActionScript {
    override val id = "hide_dev_options"
    override val displayName = "HideDevOptions"
    override val workingDirName = "hide_dev_options"

    override val operations: List<ScriptOp> = listOf(
        // === Settings$Config ===
        ScriptOp(
            key = "framework/Settings.Config.getString",
            classDescriptor = "Landroid/provider/Settings\$Config;",
            methodSignature = "getString(Ljava/lang/String;)Ljava/lang/String;",
            checkBannedWords = false
        ) { m ->
            val block = """
                invoke-static {}, Landroid/provider/Settings${'$'}Config;->getContentResolver()Landroid/content/ContentResolver;

                move-result-object v0

                invoke-virtual {v0}, Landroid/content/ContentResolver;->getPackageName()Ljava/lang/String;

                move-result-object v1

                invoke-static {v0, v1, p0}, Landroid/security/kaorios/KaoriosHook;->shouldHideDevStatus(Landroid/content/ContentResolver;Ljava/lang/String;Ljava/lang/String;)Z

                move-result v1

                if-eqz v1, :cond_kaorios

                const-string p0, "0"

                return-object p0

                :cond_kaorios
            """.trimIndent()
            SmaliEngine.insertAfterEndAnnotation(m, block)
        },

        // === Settings$Global ===
        ScriptOp(
            key = "framework/Settings.Global.getInt_ContentResolver_String",
            classDescriptor = "Landroid/provider/Settings\$Global;",
            methodSignature = "getInt(Landroid/content/ContentResolver;Ljava/lang/String;)I",
            checkBannedWords = false
        ) { m ->
            val block = """
                invoke-virtual {p0}, Landroid/content/ContentResolver;->getPackageName()Ljava/lang/String;

                move-result-object v0

                invoke-static {p0, v0, p1}, Landroid/security/kaorios/KaoriosHook;->shouldHideDevStatus(Landroid/content/ContentResolver;Ljava/lang/String;Ljava/lang/String;)Z

                move-result v0

                if-eqz v0, :cond_kaorios

                const/4 p0, 0x0

                return p0

                :cond_kaorios
            """.trimIndent()
            SmaliEngine.insertAfterEndAnnotation(m, block)
        },

        ScriptOp(
            key = "framework/Settings.Global.getInt_ContentResolver_String_Int",
            classDescriptor = "Landroid/provider/Settings\$Global;",
            methodSignature = "getInt(Landroid/content/ContentResolver;Ljava/lang/String;I)I",
            checkBannedWords = false
        ) { m ->
            val block = """
                invoke-virtual {p0}, Landroid/content/ContentResolver;->getPackageName()Ljava/lang/String;

                move-result-object v0

                invoke-static {p0, v0, p1}, Landroid/security/kaorios/KaoriosHook;->shouldHideDevStatus(Landroid/content/ContentResolver;Ljava/lang/String;Ljava/lang/String;)Z

                move-result v0

                if-eqz v0, :cond_kaorios

                const/4 p0, 0x0

                return p0

                :cond_kaorios
            """.trimIndent()
            SmaliEngine.insertAfterRegisters(m, block)
        },

        ScriptOp(
            key = "framework/Settings.Global.getString_ContentResolver_String",
            classDescriptor = "Landroid/provider/Settings\$Global;",
            methodSignature = "getString(Landroid/content/ContentResolver;Ljava/lang/String;)Ljava/lang/String;",
            checkBannedWords = false
        ) { m ->
            val block = """
                invoke-virtual {p0}, Landroid/content/ContentResolver;->getPackageName()Ljava/lang/String;

                move-result-object v0

                invoke-static {p0, v0, p1}, Landroid/security/kaorios/KaoriosHook;->shouldHideDevStatus(Landroid/content/ContentResolver;Ljava/lang/String;Ljava/lang/String;)Z

                move-result v0

                if-eqz v0, :cond_kaorios

                const-string p0, "0"

                return-object p0

                :cond_kaorios
            """.trimIndent()
            SmaliEngine.insertAfterRegisters(m, block)
        },

        ScriptOp(
            key = "framework/Settings.Global.getStringForUser",
            classDescriptor = "Landroid/provider/Settings\$Global;",
            methodSignature = "getStringForUser(Landroid/content/ContentResolver;Ljava/lang/String;I)Ljava/lang/String;",
            checkBannedWords = false
        ) { m ->
            val block = """
                invoke-virtual {p0}, Landroid/content/ContentResolver;->getPackageName()Ljava/lang/String;

                move-result-object v0

                invoke-static {p0, v0, p1}, Landroid/security/kaorios/KaoriosHook;->shouldHideDevStatus(Landroid/content/ContentResolver;Ljava/lang/String;Ljava/lang/String;)Z

                move-result v0

                if-eqz v0, :cond_kaorios

                const-string p0, "0"

                return-object p0

                :cond_kaorios
            """.trimIndent()
            SmaliEngine.insertAfterRegisters(m, block)
        },

        // === Settings$NameValueCache ===
        ScriptOp(
            key = "framework/Settings.NameValueCache.getStringForUser",
            classDescriptor = "Landroid/provider/Settings\$NameValueCache;",
            methodSignature = "getStringForUser(Landroid/content/ContentResolver;Ljava/lang/String;I)Ljava/lang/String;",
            checkBannedWords = false
        ) { m ->
            val block = """
                invoke-static/range {p1 .. p3}, Landroid/security/kaorios/KaoriosHook;->shouldHideDevStatusFromNameValueCache(Landroid/content/ContentResolver;Ljava/lang/String;I)Ljava/lang/String;

                move-result-object v0

                if-eqz v0, :cond_kaorios

                return-object v0

                :cond_kaorios
            """.trimIndent()
            SmaliEngine.insertAfterRegisters(m, block)
        },

        // === Settings$Secure ===
        ScriptOp(
            key = "framework/Settings.Secure.getInt_ContentResolver_String",
            classDescriptor = "Landroid/provider/Settings\$Secure;",
            methodSignature = "getInt(Landroid/content/ContentResolver;Ljava/lang/String;)I",
            checkBannedWords = false
        ) { m ->
            val block = """
                invoke-virtual {p0}, Landroid/content/ContentResolver;->getPackageName()Ljava/lang/String;

                move-result-object v0

                invoke-static {p0, v0, p1}, Landroid/security/kaorios/KaoriosHook;->shouldHideDevStatus(Landroid/content/ContentResolver;Ljava/lang/String;Ljava/lang/String;)Z

                move-result v0

                if-eqz v0, :cond_kaorios

                const/4 p0, 0x0

                return p0

                :cond_kaorios
            """.trimIndent()
            SmaliEngine.insertAfterEndAnnotation(m, block)
        },

        ScriptOp(
            key = "framework/Settings.Secure.getInt_ContentResolver_String_Int",
            classDescriptor = "Landroid/provider/Settings\$Secure;",
            methodSignature = "getInt(Landroid/content/ContentResolver;Ljava/lang/String;I)I",
            checkBannedWords = false
        ) { m ->
            val block = """
                invoke-virtual {p0}, Landroid/content/ContentResolver;->getPackageName()Ljava/lang/String;

                move-result-object v0

                invoke-static {p0, v0, p1}, Landroid/security/kaorios/KaoriosHook;->shouldHideDevStatus(Landroid/content/ContentResolver;Ljava/lang/String;Ljava/lang/String;)Z

                move-result v0

                if-eqz v0, :cond_kaorios

                const/4 p0, 0x0

                return p0

                :cond_kaorios
            """.trimIndent()
            SmaliEngine.insertAfterRegisters(m, block)
        },

        ScriptOp(
            key = "framework/Settings.Secure.getIntForUser_ContentResolver_String_Int",
            classDescriptor = "Landroid/provider/Settings\$Secure;",
            methodSignature = "getIntForUser(Landroid/content/ContentResolver;Ljava/lang/String;I)I",
            checkBannedWords = false
        ) { m ->
            val block = """
                invoke-virtual {p0}, Landroid/content/ContentResolver;->getPackageName()Ljava/lang/String;

                move-result-object v0

                invoke-static {p0, v0, p1}, Landroid/security/kaorios/KaoriosHook;->shouldHideDevStatus(Landroid/content/ContentResolver;Ljava/lang/String;Ljava/lang/String;)Z

                move-result v0

                if-eqz v0, :cond_kaorios

                const/4 p0, 0x0

                return p0

                :cond_kaorios
            """.trimIndent()
            SmaliEngine.insertAfterEndAnnotation(m, block)
        },

        ScriptOp(
            key = "framework/Settings.Secure.getIntForUser_ContentResolver_String_Int_Int",
            classDescriptor = "Landroid/provider/Settings\$Secure;",
            methodSignature = "getIntForUser(Landroid/content/ContentResolver;Ljava/lang/String;II)I",
            checkBannedWords = false
        ) { m ->
            val block = """
                invoke-virtual {p0}, Landroid/content/ContentResolver;->getPackageName()Ljava/lang/String;

                move-result-object v0

                invoke-static {p0, v0, p1}, Landroid/security/kaorios/KaoriosHook;->shouldHideDevStatus(Landroid/content/ContentResolver;Ljava/lang/String;Ljava/lang/String;)Z

                move-result v0

                if-eqz v0, :cond_kaorios

                const/4 p0, 0x0

                return p0

                :cond_kaorios
            """.trimIndent()
            SmaliEngine.insertAfterRegisters(m, block)
        },

        ScriptOp(
            key = "framework/Settings.Secure.getString_ContentResolver_String",
            classDescriptor = "Landroid/provider/Settings\$Secure;",
            methodSignature = "getString(Landroid/content/ContentResolver;Ljava/lang/String;)Ljava/lang/String;",
            checkBannedWords = false
        ) { m ->
            val block = """
                invoke-virtual {p0}, Landroid/content/ContentResolver;->getPackageName()Ljava/lang/String;

                move-result-object v0

                invoke-static {p0, v0, p1}, Landroid/security/kaorios/KaoriosHook;->shouldHideDevStatus(Landroid/content/ContentResolver;Ljava/lang/String;Ljava/lang/String;)Z

                move-result v0

                if-eqz v0, :cond_kaorios

                const-string p0, "0"

                return-object p0

                :cond_kaorios
            """.trimIndent()
            SmaliEngine.insertAfterRegisters(m, block)
        },

        ScriptOp(
            key = "framework/Settings.Secure.getStringForUser",
            classDescriptor = "Landroid/provider/Settings\$Secure;",
            methodSignature = "getStringForUser(Landroid/content/ContentResolver;Ljava/lang/String;I)Ljava/lang/String;",
            checkBannedWords = false
        ) { m ->
            val block = """
                invoke-virtual {p0}, Landroid/content/ContentResolver;->getPackageName()Ljava/lang/String;

                move-result-object v0

                invoke-static {p0, v0, p1}, Landroid/security/kaorios/KaoriosHook;->shouldHideDevStatus(Landroid/content/ContentResolver;Ljava/lang/String;Ljava/lang/String;)Z

                move-result v0

                const-string v1, "0"

                if-eqz v0, :cond_kaorios

                return-object v1

                :cond_kaorios
            """.trimIndent()
            SmaliEngine.insertAfterRegisters(m, block)
        },

        // === Settings$System ===
        ScriptOp(
            key = "framework/Settings.System.getInt_ContentResolver_String",
            classDescriptor = "Landroid/provider/Settings\$System;",
            methodSignature = "getInt(Landroid/content/ContentResolver;Ljava/lang/String;)I",
            checkBannedWords = false
        ) { m ->
            val block = """
                invoke-virtual {p0}, Landroid/content/ContentResolver;->getPackageName()Ljava/lang/String;

                move-result-object v0

                invoke-static {p0, v0, p1}, Landroid/security/kaorios/KaoriosHook;->shouldHideDevStatus(Landroid/content/ContentResolver;Ljava/lang/String;Ljava/lang/String;)Z

                move-result v0

                if-eqz v0, :cond_kaorios

                const/4 p0, 0x0

                return p0

                :cond_kaorios
            """.trimIndent()
            SmaliEngine.insertAfterEndAnnotation(m, block)
        },

        ScriptOp(
            key = "framework/Settings.System.getInt_ContentResolver_String_Int",
            classDescriptor = "Landroid/provider/Settings\$System;",
            methodSignature = "getInt(Landroid/content/ContentResolver;Ljava/lang/String;I)I",
            checkBannedWords = false
        ) { m ->
            val block = """
                invoke-virtual {p0}, Landroid/content/ContentResolver;->getPackageName()Ljava/lang/String;

                move-result-object v0

                invoke-static {p0, v0, p1}, Landroid/security/kaorios/KaoriosHook;->shouldHideDevStatus(Landroid/content/ContentResolver;Ljava/lang/String;Ljava/lang/String;)Z

                move-result v0

                if-eqz v0, :cond_kaorios

                const/4 p0, 0x0

                return p0

                :cond_kaorios
            """.trimIndent()
            SmaliEngine.insertAfterRegisters(m, block)
        },

        ScriptOp(
            key = "framework/Settings.System.getIntForUser_ContentResolver_String_Int",
            classDescriptor = "Landroid/provider/Settings\$System;",
            methodSignature = "getIntForUser(Landroid/content/ContentResolver;Ljava/lang/String;I)I",
            checkBannedWords = false
        ) { m ->
            val block = """
                invoke-virtual {p0}, Landroid/content/ContentResolver;->getPackageName()Ljava/lang/String;

                move-result-object v0

                invoke-static {p0, v0, p1}, Landroid/security/kaorios/KaoriosHook;->shouldHideDevStatus(Landroid/content/ContentResolver;Ljava/lang/String;Ljava/lang/String;)Z

                move-result v0

                if-eqz v0, :cond_kaorios

                const/4 p0, 0x0

                return p0

                :cond_kaorios
            """.trimIndent()
            SmaliEngine.insertAfterEndAnnotation(m, block)
        },

        ScriptOp(
            key = "framework/Settings.System.getIntForUser_ContentResolver_String_Int_Int",
            classDescriptor = "Landroid/provider/Settings\$System;",
            methodSignature = "getIntForUser(Landroid/content/ContentResolver;Ljava/lang/String;II)I",
            checkBannedWords = false
        ) { m ->
            val block = """
                invoke-virtual {p0}, Landroid/content/ContentResolver;->getPackageName()Ljava/lang/String;

                move-result-object v0

                invoke-static {p0, v0, p1}, Landroid/security/kaorios/KaoriosHook;->shouldHideDevStatus(Landroid/content/ContentResolver;Ljava/lang/String;Ljava/lang/String;)Z

                move-result v0

                if-eqz v0, :cond_kaorios

                const/4 p0, 0x0

                return p0

                :cond_kaorios
            """.trimIndent()
            SmaliEngine.insertAfterRegisters(m, block)
        },

        ScriptOp(
            key = "framework/Settings.System.getString_ContentResolver_String",
            classDescriptor = "Landroid/provider/Settings\$System;",
            methodSignature = "getString(Landroid/content/ContentResolver;Ljava/lang/String;)Ljava/lang/String;",
            checkBannedWords = false
        ) { m ->
            val block = """
                invoke-virtual {p0}, Landroid/content/ContentResolver;->getPackageName()Ljava/lang/String;

                move-result-object v0

                invoke-static {p0, v0, p1}, Landroid/security/kaorios/KaoriosHook;->shouldHideDevStatus(Landroid/content/ContentResolver;Ljava/lang/String;Ljava/lang/String;)Z

                move-result v0

                if-eqz v0, :cond_kaorios

                const-string p0, "0"

                return-object p0

                :cond_kaorios
            """.trimIndent()
            SmaliEngine.insertAfterRegisters(m, block)
        },

        ScriptOp(
            key = "framework/Settings.System.getStringForUser",
            classDescriptor = "Landroid/provider/Settings\$System;",
            methodSignature = "getStringForUser(Landroid/content/ContentResolver;Ljava/lang/String;I)Ljava/lang/String;",
            checkBannedWords = false
        ) { m ->
            val block = """
                invoke-virtual {p0}, Landroid/content/ContentResolver;->getPackageName()Ljava/lang/String;

                move-result-object v0

                invoke-static {p0, v0, p1}, Landroid/security/kaorios/KaoriosHook;->shouldHideDevStatus(Landroid/content/ContentResolver;Ljava/lang/String;Ljava/lang/String;)Z

                move-result v0

                if-eqz v0, :cond_kaorios

                const-string p0, "0"

                return-object p0

                :cond_kaorios
            """.trimIndent()
            SmaliEngine.insertAfterRegisters(m, block)
        },

        ScriptOp(
            key = "framework/Build.fields",
            classDescriptor = "Landroid/os/Build;",
            methodSignature = "<clinit>()V",
            checkBannedWords = false,
            patchMode = com.kaorios.patcher.domain.actions.PatchMode.BUILD,
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
            patchMode = com.kaorios.patcher.domain.actions.PatchMode.BUILD,
            fileTransform = { content ->
                val patched = SmaliEngine.patchBuildVersionFields(content)
                SmaliEngine.verifyBuildVersionFields(patched)
                patched
            }
        ) { }
    )
}
