# Called from C# via AndroidJavaObject / AndroidJavaProxy; R8 cannot see those references.
-keep class com.stbemu.host.** { *; }
# JNI calls these classes/methods by name.
-keep class dev.jdtech.mpv.** { *; }
