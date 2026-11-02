# gomobile bindings: Go calls back into Java by name through JNI.
-keep class go.** { *; }
-keep class io.nekohasekai.libbox.** { *; }
-keep class * implements io.nekohasekai.libbox.** { *; }
-dontwarn io.nekohasekai.libbox.**
-dontwarn go.**
