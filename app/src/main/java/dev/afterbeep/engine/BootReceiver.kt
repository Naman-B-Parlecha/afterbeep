package dev.afterbeep.engine

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dev.afterbeep.AfterbeepApp
import java.util.concurrent.TimeUnit

/** After reboot / app update: restart the engine once Wi-Fi is up (Wireless debugging needs Wi-Fi). */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        AfterbeepApp.instance.engine.slog("boot/replace broadcast: ${intent.action}")
        StatusService.start(context)
        // USB-only route needs no network: try soon after boot, independent of the Wi-Fi-gated job.
        WorkManager.getInstance(context).enqueueUniqueWork(
            "engine-usb", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<EngineCheckWorker>().setInitialDelay(20, TimeUnit.SECONDS).build()
        )
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            AfterbeepApp.instance.engine.notifyPaused()
            // Watch for Wi-Fi + the Wireless debugging switch from boot: one tap on the system tile restarts us.
            AfterbeepApp.instance.engine.startWhenReady()
        }
        AutoStartWorker.schedule(context)
    }
}

class AutoStartWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val engine = AfterbeepApp.instance.engine
        val ok = engine.autoStartIfNeeded()
        if (!ok) engine.checkUnexpectedStop()
        return if (ok || runAttemptCount > 5) Result.success() else Result.retry()
    }

    companion object {
        /** No network constraint: must also run off Wi-Fi, which is exactly when the engine is at risk. */
        fun scheduleCheck(ctx: Context) {
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                "engine-check", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<EngineCheckWorker>(15, TimeUnit.MINUTES).build()
            )
        }

        private val wifi = Constraints.Builder().setRequiredNetworkType(NetworkType.UNMETERED).build()

        fun schedule(ctx: Context) {
            val wm = WorkManager.getInstance(ctx)
            wm.enqueueUniqueWork(
                "engine-now", ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<AutoStartWorker>().setConstraints(wifi).build()
            )
            scheduleCheck(ctx)
            wm.enqueueUniquePeriodicWork(
                "engine-watchdog", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<AutoStartWorker>(15, TimeUnit.MINUTES).setConstraints(wifi).build()
            )
        }
    }
}

class EngineCheckWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val engine = AfterbeepApp.instance.engine
        if (!engine.status.value.running && engine.usbPortOpen()) engine.autoStartIfNeeded()
        engine.checkUnexpectedStop()
        return Result.success()
    }
}
