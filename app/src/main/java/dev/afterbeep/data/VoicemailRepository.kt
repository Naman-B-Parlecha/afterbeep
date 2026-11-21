package dev.afterbeep.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.sqrt

data class Voicemail(
    val id: String,
    val number: String,
    val name: String?,
    val receivedAt: Long,
    val durationMs: Long,
    val file: File,
    val unread: Boolean,
    val archived: Boolean,
) {
    val displayName: String get() = name ?: number.ifBlank { "Unknown caller" }
}

/**
 * Messages are written by the engine (shell uid) into the app's external files dir:
 *   inbox/<id>.wav + inbox/<id>.json
 * Read/archived state is app-owned (SharedPreferences).
 */
class VoicemailRepository(private val ctx: Context) {
    val dataDir: File = ctx.getExternalFilesDir(null)!!.also { it.mkdirs() }
    val inboxDir: File = File(dataDir, "inbox").also { it.mkdirs() }
    val greetingFile: File get() = File(dataDir, "greeting.wav")
    val settings = SettingsStore(ctx)

    private val statePrefs = ctx.getSharedPreferences("vm_state", Context.MODE_PRIVATE)
    private val nameCache = HashMap<String, String?>()
    private val waveCache = HashMap<String, FloatArray>()

    private val _messages = MutableStateFlow<List<Voicemail>>(emptyList())
    val messages: StateFlow<List<Voicemail>> = _messages.asStateFlow()

    suspend fun refresh() = withContext(Dispatchers.IO) {
        val list = inboxDir.listFiles { f -> f.name.endsWith(".json") && !f.name.endsWith(".tmp") }.orEmpty()
            .mapNotNull { runCatching { parse(it) }.getOrNull() }
            .filter { it.file.exists() }
            .sortedByDescending { it.receivedAt }
        _messages.value = list
    }

    private fun parse(f: File): Voicemail {
        val o = JSONObject(f.readText())
        val id = o.getString("id")
        val number = o.optString("number")
        return Voicemail(
            id = id,
            number = number,
            name = lookupName(number),
            receivedAt = o.optLong("receivedAt"),
            durationMs = o.optLong("durationMs"),
            file = File(inboxDir, o.optString("file", "$id.wav")),
            unread = !statePrefs.getBoolean("read:$id", false),
            archived = statePrefs.getBoolean("archived:$id", false),
        )
    }

    fun markRead(id: String) { statePrefs.edit().putBoolean("read:$id", true).apply(); patch(id) { it.copy(unread = false) } }
    fun markUnread(id: String) { statePrefs.edit().putBoolean("read:$id", false).apply(); patch(id) { it.copy(unread = true) } }
    fun setArchived(id: String, archived: Boolean) {
        statePrefs.edit().putBoolean("archived:$id", archived).apply(); patch(id) { it.copy(archived = archived) }
    }

    fun delete(id: String) {
        File(inboxDir, "$id.wav").delete()
        File(inboxDir, "$id.json").delete()
        statePrefs.edit().remove("read:$id").remove("archived:$id").apply()
        _messages.value = _messages.value.filterNot { it.id == id }
    }

    private fun patch(id: String, f: (Voicemail) -> Voicemail) {
        _messages.value = _messages.value.map { if (it.id == id) f(it) else it }
    }

    fun lookupName(number: String): String? {
        if (number.isBlank()) return null
        nameCache[number]?.let { return it }
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        val name = runCatching {
            ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull()
        nameCache[number] = name
        return name
    }

    /** Numbers of starred contacts, for the "favourites ring through" rule. */
    fun starredNumbers(): List<String> {
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return emptyList()
        return runCatching {
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.STARRED}=1", null, null
            )?.use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }.orEmpty()
        }.getOrDefault(emptyList())
    }

    fun forgetWaveform(file: File) { waveCache.remove(file.path) }

    /** ~[bars] normalized RMS buckets for drawing a waveform. */
    suspend fun waveform(file: File, bars: Int = 56): FloatArray = withContext(Dispatchers.IO) {
        waveCache[file.path]?.let { return@withContext it }
        val out = FloatArray(bars)
        runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val dataLen = (raf.length() - 44).coerceAtLeast(0)
                val samples = (dataLen / 2).toInt()
                if (samples == 0) return@use
                val bytes = ByteArray(dataLen.toInt())
                raf.seek(44); raf.readFully(bytes)
                val per = (samples / bars).coerceAtLeast(1)
                var max = 1e-6f
                for (b in 0 until bars) {
                    var sum = 0.0
                    val start = b * per
                    val end = minOf(samples, start + per)
                    for (i in start until end) {
                        val s = ((bytes[2 * i].toInt() and 0xff) or (bytes[2 * i + 1].toInt() shl 8)).toShort() / 32768.0
                        sum += s * s
                    }
                    out[b] = sqrt(sum / (end - start).coerceAtLeast(1)).toFloat()
                    if (out[b] > max) max = out[b]
                }
                for (b in 0 until bars) out[b] = (out[b] / max).coerceIn(0.06f, 1f)
            }
        }
        waveCache[file.path] = out
        out
    }
}
