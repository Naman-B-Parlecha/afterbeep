package dev.afterbeep.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.afterbeep.AfterbeepApp
import dev.afterbeep.audio.GreetingManager
import dev.afterbeep.audio.Player
import dev.afterbeep.audio.Wav
import dev.afterbeep.data.AppSettings
import dev.afterbeep.data.Voicemail
import dev.afterbeep.engine.AutoStartWorker
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as AfterbeepApp
    val repo = app.repository
    val engine = app.engine
    val player = Player()
    val greeting = GreetingManager(app) { repo.greetingFile }

    val messages: StateFlow<List<Voicemail>> = repo.messages
    val settings: StateFlow<AppSettings> = repo.settings.state
    val engineStatus = engine.status
    val pairing = engine.pairing
    val busy = engine.busy
    val lastError = engine.lastError

    private val _greetingInfo = MutableStateFlow(GreetingInfo())
    val greetingInfo: StateFlow<GreetingInfo> = _greetingInfo.asStateFlow()

    private val _waves = MutableStateFlow<Map<String, FloatArray>>(emptyMap())
    val waves: StateFlow<Map<String, FloatArray>> = _waves.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    init {
        viewModelScope.launch { while (isActive) { repo.refresh(); delay(3000) } }
        // A message counts as listened once ~90% of it has played (or it ran to the end).
        viewModelScope.launch {
            player.state.collect { s ->
                val id = s.key ?: return@collect
                if (id == "greeting" || s.durationMs <= 0) return@collect
                if (s.positionMs >= s.durationMs * 0.9 || s.durationMs - s.positionMs < 1500) {
                    if (repo.messages.value.any { it.id == id && it.unread }) {
                        repo.markRead(id)
                        app.getSystemService(android.app.NotificationManager::class.java).cancel(id.hashCode())
                    }
                }
            }
        }
        reloadGreeting()
        AutoStartWorker.schedule(app)
    }

    fun loadWave(vm: Voicemail) {
        if (_waves.value.containsKey(vm.id)) return
        viewModelScope.launch { _waves.value = _waves.value + (vm.id to repo.waveform(vm.file)) }
    }

    fun play(vm: Voicemail) = player.toggle(vm.id, vm.file)

    fun setUnread(vm: Voicemail, unread: Boolean) {
        if (unread) repo.markUnread(vm.id) else repo.markRead(vm.id)
        _toast.value = if (unread) "Marked as unread" else "Marked as listened"
    }

    fun archive(vm: Voicemail, archived: Boolean) {
        if (player.state.value.key == vm.id) player.release()
        repo.setArchived(vm.id, archived)
        _toast.value = if (archived) "Archived" else "Moved to inbox"
    }

    fun delete(vm: Voicemail) {
        if (player.state.value.key == vm.id) player.release()
        repo.delete(vm.id)
        _toast.value = "Deleted"
    }

    fun callBack(vm: Voicemail) {
        if (vm.number.isBlank()) return
        app.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${vm.number}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun consumeToast() { _toast.value = null }

    // ---- settings
    fun updateSettings(block: (AppSettings) -> AppSettings) {
        repo.settings.update(block)
        engine.syncConfig()
    }

    // ---- greeting
    fun reloadGreeting() {
        viewModelScope.launch {
            val f = repo.greetingFile
            _greetingInfo.value = if (f.exists()) GreetingInfo(true, Wav.durationMs(f), repo.waveform(f, 64), f.lastModified())
            else GreetingInfo()
        }
    }

    fun recordGreeting() {
        player.release()
        viewModelScope.launch {
            val ok = greeting.record()
            _toast.value = if (ok) "Greeting saved" else "Too short — try again"
            invalidateGreetingWave(); reloadGreeting()
        }
    }

    fun stopRecording() = greeting.stop()

    fun generateGreeting(text: String) {
        player.release()
        updateSettings { it.copy(greetingText = text) }
        viewModelScope.launch {
            _greetingInfo.value = _greetingInfo.value.copy(generating = true)
            val ok = greeting.synthesize(text)
            _toast.value = if (ok) "Greeting created" else "Text-to-speech failed"
            invalidateGreetingWave(); reloadGreeting()
        }
    }

    private fun invalidateGreetingWave() = repo.forgetWaveform(repo.greetingFile)

    fun playGreeting() = player.toggle("greeting", repo.greetingFile)

    override fun onCleared() { player.release() }
}

data class GreetingInfo(
    val exists: Boolean = false,
    val durationMs: Long = 0,
    val wave: FloatArray = FloatArray(0),
    val modified: Long = 0,
    val generating: Boolean = false,
)
