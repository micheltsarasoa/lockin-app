# ════════════════════════════════════════════════════════════════════════════
# LockIn ProGuard/R8 Rules
# ════════════════════════════════════════════════════════════════════════════

# ── System-referenced components (Android manifest bindings) ──────────────
# These class names are referenced by the system via AndroidManifest.xml.
# Renaming them breaks component registration.
-keep class com.lockin.admin.LockInDeviceAdminReceiver { *; }
-keep class com.lockin.admin.BootReceiver { *; }
-keep class com.lockin.vpn.LockInVpnService { *; }
-keep class com.lockin.accessibility.LockInAccessibilityService { *; }
-keep class com.lockin.app.LockInApplication { *; }
-keep class com.lockin.app.ui.MainActivity { *; }

# ── Hilt ─────────────────────────────────────────────────────────────────
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ApplicationComponentManager { *; }
-keepclasseswithmembers class * {
    @dagger.hilt.android.AndroidEntryPoint *;
}

# ── Room + SQLCipher ─────────────────────────────────────────────────────
-keep class com.lockin.filter.db.** { *; }
-keepclassmembers class * extends androidx.room.RoomDatabase { *; }
-keep class net.sqlcipher.** { *; }
-keep class net.sqlcipher.database.** { *; }

# ── Bouncy Castle (Argon2) ───────────────────────────────────────────────
-keep class org.bouncycastle.crypto.generators.Argon2BytesGenerator { *; }
-keep class org.bouncycastle.crypto.params.Argon2Parameters { *; }
-keep class org.bouncycastle.crypto.params.Argon2Parameters$Builder { *; }

# ── WorkManager ──────────────────────────────────────────────────────────
-keep class * extends androidx.work.Worker
-keep class * extends androidx.work.CoroutineWorker
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# ── OkHttp ───────────────────────────────────────────────────────────────
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }

# ── Kotlin ───────────────────────────────────────────────────────────────
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**
