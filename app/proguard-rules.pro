# Pure-Java engine — no JNI/native ABI to preserve. Keep the TTS service and
# engine classes intact (entry points referenced by the framework / manifest).
-keep class com.rosasoft.wintalker.TtsService { *; }
-keep class com.rosasoft.wintalker.engine.** { *; }
