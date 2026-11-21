package dev.afterbeep.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/** Creates greeting.wav (16-bit PCM mono) — the engine plays it into the call. */
class GreetingManager(private val ctx: Context, private val target: () -> File) {
    private val _recording = MutableStateFlow(false)
    val recording: StateFlow<Boolean> = _recording.asStateFlow()
    private val _level = MutableStateFlow(0f)
    val level: StateFlow<Float> = _level.asStateFlow()
    private val _elapsedMs = MutableStateFlow(0L)
    val elapsedMs: StateFlow<Long> = _elapsedMs.asStateFlow()

    @Volatile private var stopRequested = false

    /** Records from the mic until [stop] or [maxMs]. Writes atomically on success. */
    @SuppressLint("MissingPermission")
    suspend fun record(maxMs: Long = 30_000): Boolean = withContext(Dispatchers.IO) {
        val rate = 48_000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(min * 4, 48_000))
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return@withContext false }
        val tmp = File(target().parentFile, "greeting.rec")
        stopRequested = false
        _recording.value = true
        val start = System.currentTimeMillis()
        var bytes = 0L
        try {
            FileOutputStream(tmp).use { out ->
                out.write(ByteArray(44))
                val buf = ByteArray(4800)
                rec.startRecording()
                while (!stopRequested && System.currentTimeMillis() - start < maxMs) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n <= 0) break
                    out.write(buf, 0, n); bytes += n
                    var peak = 0
                    for (i in 0 until n - 1 step 2) peak = max(peak, abs(((buf[i].toInt() and 0xff) or (buf[i + 1].toInt() shl 8)).toShort().toInt()))
                    _level.value = peak / 32768f
                    _elapsedMs.value = System.currentTimeMillis() - start
                }
            }
        } finally {
            rec.stop(); rec.release()
            _recording.value = false
            _level.value = 0f
        }
        if (bytes < rate) { tmp.delete(); return@withContext false } // < 0.5 s
        Wav.fixHeader(tmp, rate)
        tmp.renameTo(target())
    }

    fun stop() { stopRequested = true }

    /** Synthesizes [text] with the system TTS voice into greeting.wav. */
    suspend fun synthesize(text: String): Boolean = withContext(Dispatchers.Main) {
        val ready = CompletableDeferred<Boolean>()
        lateinit var tts: TextToSpeech
        tts = TextToSpeech(ctx) { ready.complete(it == TextToSpeech.SUCCESS) }
        try {
            if (!withTimeout(10_000) { ready.await() }) return@withContext false
            tts.language = Locale.getDefault()
            tts.setSpeechRate(0.95f)
            val out = File(target().parentFile, "greeting.tts")
            val done = CompletableDeferred<Boolean>()
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { done.complete(true) }
                @Deprecated("Deprecated in Java") override fun onError(id: String?) { done.complete(false) }
            })
            tts.synthesizeToFile(text, Bundle(), out, "greeting")
            val ok = withTimeout(30_000) { done.await() }
            if (ok && out.length() > 1000) withContext(Dispatchers.IO) { out.renameTo(target()) } else false
        } catch (t: Throwable) {
            false
        } finally {
            tts.shutdown()
        }
    }
}

object Wav {
    fun fixHeader(f: File, rate: Int, channels: Int = 1) {
        RandomAccessFile(f, "rw").use { raf ->
            val dataLen = (raf.length() - 44).toInt()
            val h = ByteArray(44)
            fun put(o: Int, s: String) = s.forEachIndexed { i, c -> h[o + i] = c.code.toByte() }
            fun le16(o: Int, v: Int) { h[o] = v.toByte(); h[o + 1] = (v shr 8).toByte() }
            fun le32(o: Int, v: Int) { le16(o, v); le16(o + 2, v shr 16) }
            put(0, "RIFF"); le32(4, 36 + dataLen); put(8, "WAVE"); put(12, "fmt ")
            le32(16, 16); le16(20, 1); le16(22, channels); le32(24, rate); le32(28, rate * 2 * channels)
            le16(32, 2 * channels); le16(34, 16); put(36, "data"); le32(40, dataLen)
            raf.seek(0); raf.write(h)
        }
    }

    fun durationMs(f: File): Long = runCatching {
        RandomAccessFile(f, "r").use { raf ->
            val h = ByteArray(44); raf.readFully(h)
            val rate = (h[24].toInt() and 0xff) or ((h[25].toInt() and 0xff) shl 8) or ((h[26].toInt() and 0xff) shl 16)
            val ch = (h[22].toInt() and 0xff).coerceAtLeast(1)
            ((raf.length() - 44) * 1000 / (rate.toLong() * 2 * ch))
        }
    }.getOrDefault(0L)
}
