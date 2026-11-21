package dev.afterbeep.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.afterbeep.ui.MainViewModel
import dev.afterbeep.ui.components.BeepButton
import dev.afterbeep.ui.components.Card
import dev.afterbeep.ui.components.PlayButton
import dev.afterbeep.ui.components.SectionLabel
import dev.afterbeep.ui.components.Waveform
import dev.afterbeep.ui.components.formatDuration
import dev.afterbeep.ui.theme.Ab

@Composable
fun GreetingScreen(vm: MainViewModel) {
    val info by vm.greetingInfo.collectAsStateWithLifecycle()
    val playback by vm.player.state.collectAsStateWithLifecycle()
    val recording by vm.greeting.recording.collectAsStateWithLifecycle()
    val level by vm.greeting.level.collectAsStateWithLifecycle()
    val elapsed by vm.greeting.elapsedMs.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var text by rememberSaveable(settings.greetingText) { mutableStateOf(settings.greetingText) }
    val ctx = LocalContext.current
    val micPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) vm.recordGreeting() }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())
            .padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 130.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Greeting", style = MaterialTheme.typography.headlineMedium, color = Ab.Text)
        Text(
            "What callers hear before the beep. It plays straight into the call — nobody near your phone hears it.",
            style = MaterialTheme.typography.bodyMedium, color = Ab.Muted,
        )

        // ---- current greeting
        Card {
            SectionLabel("Now playing to callers", Modifier.padding(start = 0.dp))
            if (info.exists) {
                val mine = playback.key == "greeting"
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PlayButton(mine && playback.playing, 54.dp) { vm.playGreeting() }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Waveform(info.wave, if (mine) playback.progress else 0f, height = 44.dp)
                        Spacer(Modifier.height(4.dp))
                        Text(formatDuration(info.durationMs) + "  ·  then a beep", style = MaterialTheme.typography.labelMedium, color = Ab.Muted)
                    }
                }
            } else {
                Text("No greeting yet — callers will only hear the beep.", style = MaterialTheme.typography.bodyMedium, color = Ab.Amber)
            }
        }

        // ---- record
        Card {
            SectionLabel("Record your voice", Modifier.padding(start = 0.dp))
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                RecordButton(recording, level) {
                    when {
                        recording -> vm.stopRecording()
                        ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> vm.recordGreeting()
                        else -> micPerm.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    if (recording) "Recording · ${formatDuration(elapsed)} — tap to finish" else "Tap to record (up to 30 s)",
                    style = MaterialTheme.typography.labelLarge, color = if (recording) Ab.Coral else Ab.Muted,
                )
            }
        }

        // ---- generate
        Card {
            SectionLabel("Or generate it", Modifier.padding(start = 0.dp))
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(), minLines = 3,
                textStyle = MaterialTheme.typography.bodyLarge,
                shape = RoundedCornerShape(18.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Ab.Orange, unfocusedBorderColor = Ab.Outline,
                    focusedContainerColor = Ab.Surface2, unfocusedContainerColor = Ab.Surface2, cursorColor = Ab.Orange,
                ),
            )
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                BeepButton(
                    if (info.generating) "Creating…" else "Create with voice", enabled = !info.generating && text.isNotBlank(),
                    leading = {
                        if (info.generating) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Ab.Ink)
                        else Icon(Icons.Rounded.AutoAwesome, null, tint = Ab.Ink, modifier = Modifier.size(18.dp))
                    },
                ) { vm.generateGreeting(text) }
                Spacer(Modifier.width(12.dp))
                Text("Uses your phone's text-to-speech voice", style = MaterialTheme.typography.bodySmall, color = Ab.Faint)
            }
        }
    }
}

@Composable
private fun RecordButton(recording: Boolean, level: Float, onClick: () -> Unit) {
    val ring by animateFloatAsState(if (recording) 1f + level * 0.9f else 1f, label = "lvl")
    Box(Modifier.size(150.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(150.dp)) {
            val r = size.minDimension / 2
            drawCircle(Ab.Coral.copy(alpha = if (recording) 0.16f else 0.06f), radius = r * 0.62f * ring)
            drawCircle(
                Brush.linearGradient(listOf(Ab.Amber, Ab.Coral)), radius = r * 0.62f * ring,
                style = Stroke(if (recording) 3.dp.toPx() else 1.5.dp.toPx()), alpha = if (recording) 0.9f else 0.35f,
            )
        }
        Box(
            Modifier.size(84.dp).clip(CircleShape).background(if (recording) Brush.linearGradient(listOf(Ab.Coral, Ab.Red)) else Ab.Beep)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(if (recording) Icons.Rounded.Stop else Icons.Rounded.Mic, null, tint = Ab.Ink, modifier = Modifier.size(38.dp))
        }
    }
}
