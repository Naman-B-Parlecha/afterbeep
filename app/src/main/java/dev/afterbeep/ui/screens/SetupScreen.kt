package dev.afterbeep.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Voicemail
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.afterbeep.engine.PairingState
import dev.afterbeep.ui.MainViewModel
import dev.afterbeep.ui.components.BeepButton
import dev.afterbeep.ui.components.GhostButton
import dev.afterbeep.ui.components.Rings
import dev.afterbeep.ui.components.Wordmark
import dev.afterbeep.ui.theme.Ab

@Composable
fun SetupScreen(vm: MainViewModel, onFinish: () -> Unit) {
    val ctx = LocalContext.current
    val settings by vm.settings.collectAsStateWithLifecycle()
    val pairing by vm.pairing.collectAsStateWithLifecycle()
    val status by vm.engineStatus.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.lastError.collectAsStateWithLifecycle()
    var permsAsked by remember { mutableStateOf(false) }
    var permsTick by remember { mutableIntStateOf(0) }

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permsAsked = true; permsTick++ }
    val permsOk = remember(permsTick) {
        listOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.READ_CONTACTS)
            .all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }
    val paired = settings.paired || pairing is PairingState.Paired
    val usb by vm.engine.usbDebug.collectAsStateWithLifecycle()
    val step = when { !(permsOk || permsAsked) -> 0; !paired -> 1; !usb -> 2; !status.running -> 3; else -> 4 }

    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState())
            .padding(horizontal = 22.dp, vertical = 18.dp),
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(8.dp))
            Rings(size = 170.dp) { Icon(Icons.Rounded.Voicemail, null, tint = Ab.Orange, modifier = Modifier.size(32.dp)) }
            Spacer(Modifier.height(10.dp))
            Wordmark(size = 38)
            Spacer(Modifier.height(6.dp))
            Text("Voicemail that lives on your phone.", style = MaterialTheme.typography.bodyLarge, color = Ab.Muted)
        }
        Spacer(Modifier.height(26.dp))

        Step(1, "Allow notifications & contacts", "So new messages ping you and show caller names.", done = step > 0, active = step == 0) {
            BeepButton("Allow") {
                permLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.READ_CONTACTS))
            }
        }
        Step(
            2, "Pair with Wireless debugging",
            "Afterbeep starts its call engine the same way a computer would — but from the phone itself. One-time.",
            done = step > 1, active = step == 1,
        ) {
            when (val p = pairing) {
                PairingState.Idle, is PairingState.Failed -> {
                    if (p is PairingState.Failed) {
                        Text(p.reason, style = MaterialTheme.typography.bodySmall, color = Ab.Red); Spacer(Modifier.height(8.dp))
                    }
                    Instructions(
                        "Tap Start, then in Developer options → Wireless debugging, turn it on and choose " +
                            "“Pair device with pairing code”. Type the code into the Afterbeep notification."
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        BeepButton("Start pairing") {
                            vm.engine.startPairing()
                            ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        }
                    }
                }
                PairingState.Searching -> Waiting("Waiting for the pairing dialog… open “Pair device with pairing code”.") {
                    GhostButton("Cancel") { vm.engine.cancelPairing() }
                }
                is PairingState.AwaitingCode -> {
                    Text("Pull down the notification and type the 6-digit code — or enter it here:", style = MaterialTheme.typography.bodyMedium, color = Ab.Text)
                    Spacer(Modifier.height(8.dp))
                    CodeField { vm.engine.submitPairingCode(p.port, it) }
                }
                PairingState.Pairing -> Waiting("Pairing…")
                PairingState.Paired -> {}
            }
        }
        Step(
            3, "Keep it running off Wi-Fi",
            "Turn on USB debugging too. Without it, Afterbeep stops the moment you leave Wi-Fi. No cable needed — it only matters if you plug into a computer, and even then it asks first.",
            done = step > 2, active = step == 2,
        ) {
            Instructions("Developer options → USB debugging → On. Then come back here.")
            Spacer(Modifier.height(10.dp))
            BeepButton("Open Developer options") {
                ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        Step(4, "Start the engine", "Runs quietly in the background and answers calls you miss.", done = step > 3, active = step == 3) {
            if (busy != null) Waiting(busy!!)
            else {
                error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Ab.Red); Spacer(Modifier.height(8.dp)) }
                BeepButton("Start engine") { vm.engine.startWhenReady() }
            }
        }
        Spacer(Modifier.height(18.dp))
        if (step >= 4) BeepButton("Open my inbox", Modifier.fillMaxWidth(), onClick = onFinish)
        else Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { GhostButton("Skip for now", onClick = onFinish) }
    }
}

@Composable
private fun Step(n: Int, title: String, body: String, done: Boolean, active: Boolean, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Box(
            Modifier.size(34.dp).clip(CircleShape)
                .background(if (done || active) Ab.Beep else androidx.compose.ui.graphics.SolidColor(Ab.Surface3)),
            contentAlignment = Alignment.Center,
        ) {
            if (done) Icon(Icons.Rounded.Check, null, tint = Ab.Ink, modifier = Modifier.size(18.dp))
            else Text("$n", fontWeight = FontWeight.Black, color = if (active) Ab.Ink else Ab.Muted)
        }
        Spacer(Modifier.width(14.dp))
        Column(
            Modifier.weight(1f).clip(RoundedCornerShape(22.dp))
                .background(if (active) Ab.Surface else Ab.Ink)
                .border(1.dp, if (active) Ab.Orange.copy(alpha = 0.35f) else Ab.Outline.copy(alpha = 0.4f), RoundedCornerShape(22.dp))
                .padding(16.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = if (done) Ab.Muted else Ab.Text)
            if (!done) {
                Spacer(Modifier.height(4.dp))
                Text(body, style = MaterialTheme.typography.bodySmall, color = Ab.Muted)
            }
            if (active) { Spacer(Modifier.height(12.dp)); content() }
        }
    }
}

@Composable
private fun Instructions(text: String) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = Ab.Text.copy(alpha = 0.85f))

@Composable
private fun Waiting(text: String, extra: (@Composable () -> Unit)? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Ab.Orange)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = Ab.Text, modifier = Modifier.weight(1f))
    }
    extra?.let { Spacer(Modifier.height(10.dp)); it() }
}

@Composable
private fun CodeField(onSubmit: (String) -> Unit) {
    var code by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = code, onValueChange = { code = it.filter(Char::isDigit).take(6) },
            modifier = Modifier.weight(1f), singleLine = true, placeholder = { Text("123456") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Ab.Orange, unfocusedBorderColor = Ab.Outline, cursorColor = Ab.Orange),
        )
        Spacer(Modifier.width(10.dp))
        BeepButton("Pair", enabled = code.length == 6) { onSubmit(code) }
    }
}
