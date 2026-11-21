package dev.afterbeep.engine

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import dev.afterbeep.AfterbeepApp
import dev.afterbeep.R
import dev.afterbeep.ui.MainActivity

/**
 * Quick Settings tile — the top of the pull-down, above every notification: "Voicemail · On/Off".
 * Lit when the engine is answering calls, dim when it isn't. Tap opens Afterbeep (and starts it if off).
 */
class VoicemailTile : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        render()
    }

    override fun onClick() {
        super.onClick()
        val app = AfterbeepApp.instance
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .apply { if (!app.engine.status.value.running) putExtra(MainActivity.EXTRA_START, true) }
        startActivityAndCollapse(PendingIntent.getActivity(this, 40, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
    }

    private fun render() {
        val tile = qsTile ?: return
        val app = AfterbeepApp.instance
        val st = app.engine.status.value
        val s = app.repository.settings.state.value
        val on = st.running && s.enabled
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "Voicemail"
        tile.subtitle = when {
            on && st.state in setOf("answering", "greeting", "recording") -> "Taking a message"
            on && st.state == "ringing" -> "Incoming call"
            on -> "On"
            st.running -> "Paused"
            else -> "Off"
        }
        tile.icon = Icon.createWithResource(this, R.drawable.ic_stat_beep)
        tile.updateTile()
    }

    companion object {
        /** Ask the system to refresh the tile (it calls onStartListening if the shade is showing it). */
        fun refresh(ctx: Context) {
            runCatching { requestListeningState(ctx, ComponentName(ctx, VoicemailTile::class.java)) }
        }
    }
}
