package dev.afterbeep.ui.screens

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material.icons.rounded.Voicemail
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.afterbeep.audio.PlaybackState
import dev.afterbeep.data.Voicemail
import dev.afterbeep.engine.EngineStatus
import dev.afterbeep.ui.MainViewModel
import dev.afterbeep.ui.components.Avatar
import dev.afterbeep.ui.components.PlayButton
import dev.afterbeep.ui.components.Rings
import dev.afterbeep.ui.components.StatusPill
import dev.afterbeep.ui.components.Waveform
import dev.afterbeep.ui.components.Wordmark
import dev.afterbeep.ui.components.formatDuration
import dev.afterbeep.ui.theme.Ab

@Composable
fun InboxScreen(vm: MainViewModel, openId: String?, consumedOpen: () -> Unit, onEngineClick: () -> Unit) {
    val messages by vm.messages.collectAsStateWithLifecycle()
    val status by vm.engineStatus.collectAsStateWithLifecycle()
    val playback by vm.player.state.collectAsStateWithLifecycle()
    val waves by vm.waves.collectAsStateWithLifecycle()
    val unread = messages.count { it.unread && !it.archived }
    // 0 = Unread, 1 = All (inbox), 2 = Archived. Open on Unread whenever something is waiting.
    var tab by rememberSaveable { mutableStateOf(if (unread > 0) 0 else 1) }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    // Keep the message you're playing on screen even after it flips to "listened".
    val list = when (tab) {
        0 -> messages.filter { !it.archived && (it.unread || it.id == expanded) }
        1 -> messages.filter { !it.archived }
        else -> messages.filter { it.archived }
    }
    val showArchived = tab == 2
    val listState = rememberLazyListState()

    LaunchedEffect(openId, messages.size) {
        if (openId != null && messages.any { it.id == openId }) {
            tab = if (messages.first { it.id == openId }.archived) 2 else 0; expanded = openId; consumedOpen()
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 130.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Wordmark()
                Spacer(Modifier.weight(1f))
                EngineIndicator(status, onEngineClick)
            }
        }
        item {
            Text(
                if (unread > 0) "$unread unheard ${if (unread == 1) "message" else "messages"}" else "You're all caught up",
                style = MaterialTheme.typography.bodyMedium, color = Ab.Muted, modifier = Modifier.padding(start = 2.dp, bottom = 8.dp),
            )
        }
        item {
            Segmented(
                listOf("Unread" to unread, "All" to messages.count { !it.archived }, "Archived" to messages.count { it.archived }),
                tab,
            ) { tab = it; expanded = null }
            Spacer(Modifier.height(6.dp))
        }
        if (list.isEmpty()) {
            item { EmptyState(tab, status.running) }
        } else {
            items(list, key = { it.id }) { m ->
                LaunchedEffect(m.id) { vm.loadWave(m) }
                MessageCard(
                    m = m,
                    expanded = expanded == m.id,
                    wave = waves[m.id],
                    playback = playback,
                    onClick = { expanded = if (expanded == m.id) null else m.id },
                    onPlay = { vm.play(m) },
                    onSeek = { if (playback.key == m.id) vm.player.seek(it) else { vm.play(m); vm.player.seek(it) } },
                    onSpeed = vm.player::cycleSpeed,
                    onCall = { vm.callBack(m) },
                    onArchive = { vm.archive(m, !m.archived); expanded = null },
                    onToggleRead = { vm.setUnread(m, !m.unread) },
                    onDelete = { vm.delete(m); expanded = null },
                )
            }
        }
    }
}

@Composable
private fun EngineIndicator(status: EngineStatus, onClick: () -> Unit) {
    val (label, color) = when {
        !status.running -> "Off" to Ab.Red
        status.state == "ringing" -> "Incoming…" to Ab.Amber
        status.state in setOf("answering", "greeting", "recording") -> "Taking a message" to Ab.Coral
        else -> "Listening" to Ab.Mint
    }
    StatusPill(label, color, pulsing = status.running, onClick = onClick)
}

@Composable
private fun Segmented(options: List<Pair<String, Int>>, selected: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(Ab.Surface).border(1.dp, Ab.Outline.copy(0.6f), RoundedCornerShape(50)).padding(4.dp)
    ) {
        options.forEachIndexed { i, (label, count) ->
            val sel = i == selected
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(50))
                    .background(if (sel) Ab.Surface3 else Color.Transparent)
                    .clickable { onSelect(i) }.padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(label, style = MaterialTheme.typography.labelLarge, color = if (sel) Ab.Text else Ab.Muted)
                if (count > 0) {
                    Spacer(Modifier.width(6.dp))
                    Text("$count", style = MaterialTheme.typography.labelMedium, color = if (sel) Ab.Orange else Ab.Faint)
                }
            }
        }
    }
}

@Composable
private fun MessageCard(
    m: Voicemail, expanded: Boolean, wave: FloatArray?, playback: PlaybackState,
    onClick: () -> Unit, onPlay: () -> Unit, onSeek: (Float) -> Unit, onSpeed: () -> Unit,
    onCall: () -> Unit, onArchive: () -> Unit, onDelete: () -> Unit, onToggleRead: () -> Unit,
) {
    val mine = playback.key == m.id
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(if (expanded) Ab.Surface2 else Ab.Surface)
            .border(1.dp, if (expanded) Ab.Orange.copy(alpha = 0.35f) else Ab.Outline.copy(0.5f), RoundedCornerShape(24.dp))
            .clickable(onClick = onClick)
            .animateContentSize(spring(stiffness = 500f))
            .padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Avatar(m.name, m.number.ifBlank { m.id })
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    m.displayName, style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (m.unread) FontWeight.ExtraBold else FontWeight.SemiBold,
                    color = Ab.Text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "${relative(m.receivedAt)} · ${formatDuration(m.durationMs)}",
                    style = MaterialTheme.typography.bodySmall, color = Ab.Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            if (m.unread) Box(Modifier.size(10.dp).clip(CircleShape).background(Ab.Beep))
            else if (!expanded && wave != null) Waveform(wave.sliceArray(0 until minOf(14, wave.size)), 0f, Modifier.width(52.dp), height = 22.dp)
        }
        AnimatedVisibility(expanded, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
            Column(Modifier.padding(top = 16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PlayButton(mine && playback.playing, 52.dp, onPlay)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Waveform(wave ?: FloatArray(0), if (mine) playback.progress else 0f, height = 40.dp, onSeek = onSeek)
                        Spacer(Modifier.height(6.dp))
                        Row {
                            Text(
                                formatDuration(if (mine) playback.positionMs else 0), style = MaterialTheme.typography.labelMedium, color = Ab.Muted,
                            )
                            Spacer(Modifier.weight(1f))
                            Text(formatDuration(m.durationMs), style = MaterialTheme.typography.labelMedium, color = Ab.Faint)
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Box(
                        Modifier.clip(RoundedCornerShape(50)).background(Ab.Surface3).clickable(onClick = onSpeed).padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text("${playback.speed.let { if (it % 1f == 0f) it.toInt().toString() else it.toString() }}×", style = MaterialTheme.typography.labelMedium, color = Ab.Text)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (m.unread) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(Ab.Beep))
                        Spacer(Modifier.width(6.dp))
                        Text("Unheard — stays here until you listen", style = MaterialTheme.typography.labelMedium, color = Ab.Amber)
                    } else {
                        Text("Listened", style = MaterialTheme.typography.labelMedium, color = Ab.Faint)
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        if (m.unread) "Mark listened" else "Mark unread",
                        style = MaterialTheme.typography.labelMedium, color = Ab.Muted,
                        modifier = Modifier.clip(RoundedCornerShape(50)).clickable(onClick = onToggleRead).padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Action(Icons.Rounded.Call, "Call back", Ab.Mint, Modifier.weight(1f), enabled = m.number.isNotBlank(), onClick = onCall)
                    Action(if (m.archived) Icons.Rounded.Unarchive else Icons.Rounded.Archive, if (m.archived) "Inbox" else "Archive", Ab.Text, Modifier.weight(1f), onClick = onArchive)
                    Action(Icons.Rounded.DeleteOutline, "Delete", Ab.Red, Modifier.weight(1f), onClick = onDelete)
                }
            }
        }
    }
}

@Composable
private fun Action(icon: ImageVector, label: String, tint: Color, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(16.dp)).background(Ab.Surface3.copy(alpha = if (enabled) 1f else 0.4f))
            .clickable(enabled = enabled, onClick = onClick).padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint.copy(alpha = if (enabled) 1f else 0.4f), modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = tint.copy(alpha = if (enabled) 1f else 0.4f))
    }
}

@Composable
private fun EmptyState(tab: Int, running: Boolean) {
    val archived = tab == 2
    Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Rings(size = 190.dp) { Icon(Icons.Rounded.Voicemail, null, tint = Ab.Orange, modifier = Modifier.size(34.dp)) }
        Spacer(Modifier.height(18.dp))
        Text(when (tab) { 0 -> "All caught up"; 2 -> "Nothing archived"; else -> "No messages yet" }, style = MaterialTheme.typography.titleLarge, color = Ab.Text)
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                archived -> "Messages you archive will rest here."
                tab == 0 -> "Every message has been listened to."
                running -> "When you can't pick up, Afterbeep answers,\nplays your greeting and keeps the message here."
                else -> "The engine is off — tap the status pill to start it."
            },
            style = MaterialTheme.typography.bodyMedium, color = Ab.Muted, textAlign = TextAlign.Center,
        )
    }
}

private fun relative(t: Long): String =
    DateUtils.getRelativeTimeSpanString(t, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS, DateUtils.FORMAT_ABBREV_RELATIVE).toString()
