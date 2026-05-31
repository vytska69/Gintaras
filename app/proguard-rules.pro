# The native engine (librosasofttts.so) binds to TtsService instance fields and
# native methods by their exact names/signatures via JNI. Renaming breaks the ABI.
-keep class com.rosasoft.wintalker.TtsService { *; }
-keepclasseswithmembernames class * {
    native <methods>;
}
