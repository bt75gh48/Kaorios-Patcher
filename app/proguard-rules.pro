# ===============================================================================
# KaoriosPatcher — ProGuard / R8 rules
# ===============================================================================

-keepattributes SourceFile,LineNumberTable,*Annotation*,EnclosingMethod,InnerClasses,Signature
-renamesourcefileattribute SourceFile

# -------------------------------------------------------------------------------
# Desktop-only Java classes được jadx / antlr / stringtemplate tham chiếu qua
# code path không dùng trên Android. Silence R8 warnings.
# -------------------------------------------------------------------------------
-dontwarn javax.xml.stream.**
-dontwarn java.awt.**
-dontwarn java.awt.image.**
-dontwarn javax.swing.**
-dontwarn javax.swing.border.**
-dontwarn javax.swing.event.**
-dontwarn javax.swing.text.**
-dontwarn javax.swing.tree.**
-dontwarn javax.imageio.**
-dontwarn org.stringtemplate.v4.gui.**
-dontwarn jadx.api.gui.**
-dontwarn jadx.api.plugins.gui.**
-dontwarn jadx.core.utils.android.Res9patchStreamDecoder

# -------------------------------------------------------------------------------
# jadx — plugins load qua ServiceLoader (SPI). KHÔNG được obfuscate/minify class
# plugin + implementation mặc định, nếu không getResolvedPluginContexts() trả rỗng
# và load .dex fail (đã gặp bug này trước — xem log Smali2Java trước đây).
# -------------------------------------------------------------------------------
-keep class jadx.** { *; }
-keep interface jadx.** { *; }
-keepclassmembers class jadx.** { *; }

# ServiceLoader metadata
-keep class ** implements jadx.api.plugins.JadxPlugin { *; }
-keep class ** implements jadx.api.plugins.input.JadxCodeInput { *; }
-keepnames class * extends jadx.api.plugins.JadxPlugin
-keepclassmembers class META-INF.services.** { *; }

# -------------------------------------------------------------------------------
# smali / baksmali / dexlib2 — app gọi Smali.assemble qua reflection, cần giữ
# tên method và các class liên quan.
# -------------------------------------------------------------------------------
-keep class com.android.tools.smali.** { *; }
-keep interface com.android.tools.smali.** { *; }
-keepclassmembers class com.android.tools.smali.** { *; }

# Antlr (dùng bởi smali)
-keep class org.antlr.** { *; }
-dontwarn org.antlr.**

# -------------------------------------------------------------------------------
# kotlinx.serialization — cần giữ các @Serializable classes + default ctor
# -------------------------------------------------------------------------------
-keepattributes InnerClasses
-keep,includedescriptorclasses class com.kaorios.patcher.**$$serializer { *; }
-keepclassmembers class com.kaorios.patcher.** {
    *** Companion;
}
-keepclasseswithmembers class com.kaorios.patcher.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# -------------------------------------------------------------------------------
# Reflection ở app: Smali2Java.kt dùng reflection đọc class method 'assemble',
# pickClasses reflect 'getClassesWithInners' / 'getRoot' / 'convertClassNode' của jadx.
# Đã cover bởi -keep jadx.** nhưng giữ safe.
# -------------------------------------------------------------------------------
-keepclasseswithmembernames class * {
    native <methods>;
}

# -------------------------------------------------------------------------------
# Compose / Kotlin / AndroidX standard — AGP đã có default, chỉ silent warning.
# -------------------------------------------------------------------------------
-dontwarn kotlin.**
-dontwarn kotlinx.**
-dontwarn sun.**
-dontwarn java.beans.**

# -------------------------------------------------------------------------------
# Sora-editor — giữ class liên quan TextMate + events để reflection của editor
# không fail khi minify.
# -------------------------------------------------------------------------------
-keep class io.github.rosemoe.sora.** { *; }
-keep class org.eclipse.tm4e.** { *; }
-dontwarn org.eclipse.tm4e.**
-dontwarn io.github.rosemoe.sora.**

# -------------------------------------------------------------------------------
# Joni + JCodings — tm4e OnigRegExp dùng org.joni.Regex + org.jcodings UTF8Encoding.
# R8 rename/shrink các class này → ExceptionInInitializerError / NPE trong <clinit>
# (stack release: o4/w22 — tên obfuscate của joni inner classes, ví dụ QuantifierNode).
# -------------------------------------------------------------------------------
-keep class org.joni.** { *; }
-keep interface org.joni.** { *; }
-keepclassmembers class org.joni.** { *; }
-keep class org.jcodings.** { *; }
-keep interface org.jcodings.** { *; }
-keepclassmembers class org.jcodings.** { *; }

# -------------------------------------------------------------------------------
# Guava / Protobuf runtime hints — Guava pull nhiều optional deps
# -------------------------------------------------------------------------------
-dontwarn com.google.errorprone.**
-dontwarn com.google.j2objc.**
-dontwarn org.checkerframework.**
-dontwarn afu.org.checkerframework.**
-dontwarn javax.annotation.**
