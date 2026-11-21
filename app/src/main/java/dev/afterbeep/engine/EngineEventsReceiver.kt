package dev.afterbeep.engine

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.afterbeep.AfterbeepApp
import dev.afterbeep.R
import dev.afterbeep.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Engine -> app: a new message was saved. Refresh the inbox and notify. */
class EngineEventsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "dev.afterbeep.ADB_BLOCKED") { notifyBlocked(context, intent.getStringExtra("fp") ?: "?"); return }
        val id = intent.getStringExtra("id") ?: return
        val pending = goAsync()
        val app = AfterbeepApp.instance
        CoroutineScope(Dispatchers.IO).launch {
            try {
                app.repository.refresh()
                val vm = app.repository.messages.value.firstOrNull { it.id == id } ?: return@launch
                val secs = vm.durationMs / 1000
                val open = PendingIntent.getActivity(
                    context, id.hashCode(),
                    Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_OPEN_ID, id)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                val n = NotificationCompat.Builder(context, AfterbeepApp.CH_MESSAGES)
                    .setSmallIcon(R.drawable.ic_stat_beep)
                    .setColor(0xFFFF8A4C.toInt())
                    .setContentTitle(vm.displayName)
                    .setContentText("Left you a message · %d:%02d".format(secs / 60, secs % 60))
                    .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                    .setPriority(NotificationCompat.PRIORITY_HIGH)
                    .setAutoCancel(true)
                    .setWhen(vm.receivedAt).setShowWhen(true)
                    .setDefaults(NotificationCompat.DEFAULT_ALL)
                    .setContentIntent(open)
                    .addAction(R.drawable.ic_stat_beep, "Listen", open)
                    .apply {
                        if (vm.number.isNotBlank()) addAction(
                            R.drawable.ic_stat_beep, "Call back",
                            PendingIntent.getActivity(
                                context, id.hashCode() + 1,
                                Intent(Intent.ACTION_DIAL, android.net.Uri.parse("tel:${vm.number}")),
                                PendingIntent.FLAG_IMMUTABLE,
                            ),
                        )
                    }
                    .build()
                context.getSystemService(NotificationManager::class.java).notify(id.hashCode(), n)
            } finally {
                pending.finish()
            }
        }
    }

    private fun notifyBlocked(context: Context, fp: String) {
        AfterbeepApp.instance.engine.slog("guard blocked a network debugging request $fp")
        val n = NotificationCompat.Builder(context, AfterbeepApp.CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_beep)
            .setColor(0xFFFF5470.toInt())
            .setContentTitle("Blocked a connection attempt")
            .setContentText("Something asked for debugging access to your phone. Afterbeep rejected it.")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "Something (a device on this network, or a computer on a USB cable) asked for debugging access to your phone. Afterbeep rejected it automatically — nothing was allowed. To add a computer of your own, pair it with Wireless debugging → Pair device with pairing code. Key $fp"))
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(fp.hashCode(), n)
    }
}
