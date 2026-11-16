# 保留 Compose 与 Lifecycle 关键类，防止 R8 裁剪导致崩溃
-keep class androidx.compose.ui.platform.AndroidCompositionLocals_androidKt { *; }
-keep class androidx.lifecycle.** { *; }
-dontwarn androidx.compose.**

# 保留 Room 实体与 Dao
-keep class com.downloadsource.data.** { *; }
-keepclassmembers class * {
    @androidx.room.* <fields>;
}

# 保留 Media3 播放器
-keep class androidx.media3.** { *; }

# 保留 WebView JS 桥接接口
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.downloadsource.web.WebInterceptor { *; }

# 保留 Glance 小组件相关类
-keep class androidx.glance.** { *; }
