# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile
-keep class com.baidu.bottom.** { *; }
-keep class com.baidu.mobstat.** { *; }

# ==================== 本项目规则 ====================
# 应用自身类全部保留：项目里 Gson 反射、动态类名、泛型较多，避免误删
-keep class com.networktool.app.** { *; }
-keep interface com.networktool.app.** { *; }
-keep enum com.networktool.app.** { *; }

# Gson 用到的泛型签名与注解
-keepattributes Signature, *Annotation*
-keepclassmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
