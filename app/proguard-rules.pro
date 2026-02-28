-keep class com.krypt.app.** { *; }
-keep class org.webrtc.** { *; }
-keepclassmembers class * {
    @androidx.room.* <methods>;
}
-dontwarn org.webrtc.**
