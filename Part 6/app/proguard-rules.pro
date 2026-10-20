# Proguard Rules for JARVIS Assistant

# Keep Gson model classes
-keep class com.jarvis.assistant.data.model.** { *; }
-keepattributes Signature
-keepattributes *Annotation*

# Keep JavascriptInterfaces for WebViews (HUD, Settings, & Floating Orbs)
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep Vosk offline wake word and JNA native libraries
-keep class org.vosk.** { *; }
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.** { *; }
-keep class net.java.dev.jna.** { *; }

# Keep Room database entities & DAOs
-keep class androidx.room.** { *; }
-keep class * extends androidx.room.RoomDatabase
-keep class com.jarvis.assistant.data.memory.** { *; }
