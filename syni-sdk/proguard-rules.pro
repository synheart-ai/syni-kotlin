# ProGuard rules for syni-sdk library

# Keep the JNI methods for PortableLocalEngine
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep enum values
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
