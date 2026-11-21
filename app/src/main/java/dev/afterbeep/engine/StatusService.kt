package dev.afterbeep.engine

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import dev.afterbeep.AfterbeepApp
import dev.afterbeep.R
import dev.afterbeep.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Always-on, silent notification showing whether voicemail is On or Off right now — no surprises.
 * Runs as a small foreground service that mirrors the engine's heartbeat (the engine itself runs as shell
 * and can't own a notification; and if it dies, something else has to say "Off").
 */
class StatusService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val app = AfterbeepApp.instance
        startForeground(ID, build(View.from(app)), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        // This service keeps our process alive, so it's also where we keep watching for a chance to start.
        if (!app.engine.status.value.running && app.repository.settings.state.value.paired) app.engine.startWhenReady()
        scope.launch {
            combine(app.engine.status, app.engine.usbDebug, app.engine.busy, app.repository.settings.state) { _, _, _, _ ->
                View.from(app)
            }.distinctUntilChanged().collect { v ->
                runCatching { NotificationManagerCompat.from(this@StatusService).notify(ID, build(v)) }
                VoicemailTile.refresh(this@StatusService)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    /** Everything the notification shows; equality drives updates. */
    private data class View(val on: Boolean, val title: String, val text: String, val canStart: Boolean) {
        companion object {
            fun from(app: AfterbeepApp): View {
                val st = app.engine.status.value
                val s = app.repository.settings.state.value
                val busy = app.engine.busy.value
                return when {
                    busy != null -> View(false, "Voicemail starting…", busy, false)
                    st.running && !s.enabled -> View(false, "Voicemail paused", "“Take messages” is off — calls ring normally", false)
                    st.running -> View(
                        true, "Voicemail on",
                        when (st.state) {
                            "ringing" -> "Incoming call — answering if you don't pick up"
                            "answering", "greeting", "recording" -> "Taking a message…"
                            else -> "Listening · answers after ${s.ringSeconds} s" +
                                if (!app.engine.usbDebugOn) " · ⚠ USB debugging off" else ""
                        },
                        false,
                    )
                    else -> View(
                        false, "Voicemail off",
                        app.engine.diagnose()?.let { "Calls ring normally. $it" } ?: "Calls ring normally. Tap to start.",
                        true,
                    )
                }
            }
        }
    }

    private fun build(v: View): Notification {
        val open = PendingIntent.getActivity(
            this, 20, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, AfterbeepApp.CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat_beep)
            .setColor(if (v.on) 0xFF43E5A0.toInt() else 0xFFFF5470.toInt())
            .setContentTitle(v.title)
            .setContentText(v.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(v.text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            // Android 16 "Live Update": pinned to the top of the shade + a status-bar chip ("On"/"Off").
            .setRequestPromotedOngoing(true)
            .setShortCriticalText(if (v.on) "VM on" else "VM off")
            .setContentIntent(open)
            .apply {
                if (v.canStart) addAction(
                    R.drawable.ic_stat_beep, "Start",
                    PendingIntent.getActivity(
                        this@StatusService, 21,
                        Intent(this@StatusService, MainActivity::class.java).putExtra(MainActivity.EXTRA_START, true)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                )
            }
            .build()
    }

    companion object {
        const val ID = 30

        /** Safe to call often; only allowed while the app is in the foreground or from boot. */
        fun start(ctx: Context) {
            if (!AfterbeepApp.instance.repository.settings.state.value.statusNotification) return
            runCatching { ContextCompat.startForegroundService(ctx, Intent(ctx, StatusService::class.java)) }
        }

        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, StatusService::class.java)) }
    }
}
