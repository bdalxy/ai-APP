# ProGuard / R8 - AI Companion App

# Chaquopy Python
-keep class com.chaquo.python.** { *; }
-dontwarn com.chaquo.python.**

# Kotlin serialization
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses

# App core classes
-keep class com.aicompanion.app.AICompanionApp { *; }
-keep class com.aicompanion.app.MainActivity { *; }
-keep class com.aicompanion.app.Message { *; }
-keep class com.aicompanion.app.Message$MessageStatus { *; }
-keep class com.aicompanion.app.AppConfig { *; }
-keep class com.aicompanion.app.CharacterData { *; }
-keep class com.aicompanion.app.ConversationSessionManager { *; }
-keep class com.aicompanion.app.ConversationSession { *; }
-keep class com.aicompanion.app.PerformanceMonitor { *; }

# ViewBinding
-keep class * extends androidx.viewbinding.ViewBinding { *; }

# RecyclerView Adapter
-keep class com.aicompanion.app.ChatAdapter { *; }
-keep class com.aicompanion.app.ChatAdapter$* { *; }

# JSON serialization
-keepclassmembers class * {
    @org.json.JSONObject <methods>;
}

# Parcelable
-keepclassmembers class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}
-keepclassmembers class * {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
}

# Remove debug logs (release)
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}