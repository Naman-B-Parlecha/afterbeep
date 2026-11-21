package dev.afterbeep

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.work.Configuration
import dev.afterbeep.data.VoicemailRepository
import dev.afterbeep.engine.EngineController
import org.conscrypt.Conscrypt
import java.security.Security

class AfterbeepApp : Application(), Configuration.Provider {

    lateinit var repository: VoicemailRepository
        private set
    lateinit var engine: EngineController
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        // TLS 1.3 for adb pairing / connect (libadb-android)
        Security.insertProviderAt(Conscrypt.newProvider(), 1)

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_MESSAGES, "New voicemails", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "A caller left you a message" }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_SETUP, "Setup & engine", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Pairing and engine status" }
        )

        nm.createNotificationChannel(
            // HIGH so it ranks at the top of the shade; it's always posted silent, so it never pops up as a banner.
            NotificationChannel(CH_STATUS, "Voicemail status (always on)", NotificationManager.IMPORTANCE_HIGH)
                .apply {
                    description = "Shows whether voicemail is on or off right now"
                    setShowBadge(false); setSound(null, null); enableVibration(false); enableLights(false)
                }
        )
        // Older builds used lower-importance channels; importance can't be raised in place.
        nm.deleteNotificationChannel("status"); nm.deleteNotificationChannel("status_v2")

        repository = VoicemailRepository(this)
        engine = EngineController(this)
        engine.syncConfig()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().build()

    companion object {
        const val CH_MESSAGES = "messages"
        const val CH_SETUP = "setup"
        const val CH_STATUS = "status_v3"
        lateinit var instance: AfterbeepApp
            private set
    }
}
