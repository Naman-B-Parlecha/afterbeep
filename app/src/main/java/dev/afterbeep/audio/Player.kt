package dev.afterbeep.audio

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.PlaybackParams
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

data class PlaybackState(
    val key: String? = null,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
) {
    val progress: Float get() = if (durationMs > 0) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
}

/** Single shared player for messages and greeting previews. */
class Player {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var mp: MediaPlayer? = null
    private var ticker: Job? = null
    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    fun toggle(key: String, file: File) {
        val s = _state.value
        if (s.key == key && mp != null) {
            if (s.playing) pause() else resume()
            return
        }
        release()
        val p = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            setDataSource(file.absolutePath)
            prepare()
            setOnCompletionListener { _state.value = _state.value.copy(playing = false, positionMs = _state.value.durationMs); stopTicker() }
        }
        mp = p
        _state.value = PlaybackState(key, false, 0, p.duration.toLong(), s.speed)
        resume()
    }

    fun seek(fraction: Float) {
        val p = mp ?: return
        val pos = (fraction * p.duration).toInt()
        p.seekTo(pos)
        _state.value = _state.value.copy(positionMs = pos.toLong())
    }

    fun cycleSpeed() {
        val next = when (_state.value.speed) { 1f -> 1.5f; 1.5f -> 2f; else -> 1f }
        _state.value = _state.value.copy(speed = next)
        mp?.let { if (it.isPlaying) it.playbackParams = PlaybackParams().setSpeed(next) }
    }

    private fun resume() {
        val p = mp ?: return
        if (_state.value.positionMs >= _state.value.durationMs - 50) p.seekTo(0)
        p.playbackParams = PlaybackParams().setSpeed(_state.value.speed)
        p.start()
        _state.value = _state.value.copy(playing = true)
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                mp?.let { _state.value = _state.value.copy(positionMs = it.currentPosition.toLong()) }
                delay(50)
            }
        }
    }

    fun pause() {
        mp?.pause()
        _state.value = _state.value.copy(playing = false)
        stopTicker()
    }

    private fun stopTicker() { ticker?.cancel(); ticker = null }

    fun release() {
        stopTicker()
        mp?.release()
        mp = null
        _state.value = PlaybackState(speed = _state.value.speed)
    }
}
