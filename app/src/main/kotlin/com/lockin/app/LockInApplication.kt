package com.lockin.app

import android.app.Application
import androidx.work.Configuration
import com.lockin.security.TamperDetector
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Application entry point. Hilt generates a component here.
 *
 * On startup:
 *   1. Runs tamper detection (off main thread)
 *   2. Initializes the filter engine
 *   3. WorkManager is initialized via [Configuration.Provider]
 */
@HiltAndroidApp
class LockInApplication : Application(), Configuration.Provider {

    @Inject lateinit var tamperDetector: TamperDetector
    @Inject lateinit var workerFactory: androidx.hilt.work.HiltWorkerFactory

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        appScope.launch { runTamperCheck() }
    }

    private fun runTamperCheck() {
        val result = tamperDetector.check()
        if (result is TamperDetector.TamperResult.Tampered) {
            android.util.Log.e("LockIn", "TAMPER DETECTED: ${result.reason}")
            // In production: lock the app UI, notify parent via FCM (future feature)
            // For now: log the event — the filter continues running
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()
}
