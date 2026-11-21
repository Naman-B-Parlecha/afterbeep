package dev.afterbeep.adb

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import dev.afterbeep.AfterbeepApp

/** Receives the pairing code typed into the notification (so the Wireless debugging dialog stays open). */
class PairingReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_CODE)?.toString() ?: return
        val port = intent.getIntExtra(EXTRA_PORT, 0)
        if (port > 0) AfterbeepApp.instance.engine.submitPairingCode(port, code)
    }

    companion object {
        const val KEY_CODE = "code"
        const val EXTRA_PORT = "port"
    }
}
