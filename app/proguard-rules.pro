# Hilt generated graph/aggregated deps.
-keep class dagger.hilt.internal.** { *; }
-keep class hilt_aggregated_deps.** { *; }
-keep class **_HiltModules_* { *; }
-keep class com.iptv.tv.Hilt_* { *; }

# WorkManager workers are instantiated by class name.
-keep class * extends androidx.work.ListenableWorker { *; }

# Room generated database access.
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }

# Media3 PlayerView used via AndroidView interop.
-keep class androidx.media3.ui.PlayerView { <init>(...); }

# Keep model members used by serialization/mapping.
-keepclassmembers class com.iptv.tv.core.model.** { *; }

# Optional provider / annotations noise in transitive deps.
-dontwarn org.conscrypt.**
-dontwarn javax.annotation.**
