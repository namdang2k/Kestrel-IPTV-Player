# Called from C# via AndroidJavaObject / AndroidJavaProxy; R8 cannot see those references.
-keep class com.stbemu.host.** { *; }
