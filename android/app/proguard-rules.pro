# ==========================================
# ProGuard / R8 规则 - AI Companion App
# ==========================================

# ── Chaquopy Python 保护（不可删除）──
-keep class com.chaquo.python.** { *; }
-dontwarn com.chaquo.python.**

# ── Kotlin 序列化保护 ──
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses

# ── 应用核心类保护（避免 R8 误删）──
-keep class com.aicompanion.app.AICompanionApp { *; }
-keep class com.aicompanion.app.MainActivity { *; }
-keep class com.aicompanion.app.Message { *; }
-keep class com.aicompanion.app.Message$MessageStatus { *; }
-keep class com.aicompanion.app.AppConfig { *; }
-keep class com.aicompanion.app.CharacterData { *; }
-keep class com.aicompanion.app.ConversationSessionManager { *; }
-keep class com.aicompanion.app.ConversationSession { *; }
-keep class com.aicompanion.app.PerformanceMonitor { *; }

# ── ViewBinding 保护 ──
-keep class * extends androidx.viewbinding.ViewBinding { *; }

# ── RecyclerView Adapter 保护 ──
-keep class com.aicompanion.app.ChatAdapter { *; }
-keep class com.aicompanion.app.ChatAdapter$* { *; }

# ── JSON 序列化保护 ──
-keepclassmembers class * {
    @org.json.JSONObject <methods>;
}

# ── 通用保护 ──
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}
-keepclassmembers class * {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
}

# ── 移除调试日志（Release 构建）──
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}