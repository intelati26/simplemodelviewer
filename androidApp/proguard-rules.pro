# JNI: the native library looks these up by name
-keep class org.spcrefs.app.NativeCore { *; }
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
