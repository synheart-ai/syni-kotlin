# Consumer ProGuard rules for syni-sdk

# Keep all public API classes
-keep class com.syni.sdk.Syni { *; }
-keep class com.syni.sdk.core.** { *; }

# Keep Kotlin serialization classes
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep serializable classes
-keep,includedescriptorclasses class com.syni.sdk.**$$serializer { *; }
-keepclassmembers class com.syni.sdk.** {
    *** Companion;
}
-keepclasseswithmembers class com.syni.sdk.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp rules
-dontwarn okhttp3.**
-dontwarn okio.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
