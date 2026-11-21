package dev.afterbeep.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.afterbeep.ui.screens.GreetingScreen
import dev.afterbeep.ui.screens.InboxScreen
import dev.afterbeep.ui.screens.SettingsScreen
import dev.afterbeep.ui.screens.SetupScreen
import dev.afterbeep.ui.theme.Ab
import dev.afterbeep.ui.theme.AfterbeepTheme

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()
    private val openId = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.Transparent.toArgb()),
            navigationBarStyle = SystemBarStyle.dark(Color.Transparent.toArgb()),
        )
        super.onCreate(savedInstanceState)
        openId.value = intent.getStringExtra(EXTRA_OPEN_ID)
        handleStart(intent)
        dev.afterbeep.engine.StatusService.start(this)
        setContent { AfterbeepTheme { Root(vm, openId.value) { openId.value = null } } }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        openId.value = intent.getStringExtra(EXTRA_OPEN_ID)
        handleStart(intent)
    }

    /** From the "Voicemail is paused" notification: start now, or open Wireless debugging and start when it's on. */
    private fun handleStart(intent: android.content.Intent) {
        if (!intent.getBooleanExtra(EXTRA_START, false)) return
        intent.removeExtra(EXTRA_START)
        val needsSwitch = vm.engine.diagnose() != null
        vm.engine.startWhenReady()
        if (needsSwitch) startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
    }

    companion object { const val EXTRA_OPEN_ID = "open_id"; const val EXTRA_START = "start_engine" }
}

enum class Tab(val label: String) { Inbox("Inbox"), Greeting("Greeting"), Settings("Settings") }

@Composable
private fun Root(vm: MainViewModel, openId: String?, consumedOpen: () -> Unit) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val toast by vm.toast.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableStateOf(Tab.Inbox) }

    LaunchedEffect(toast) { toast?.let { snack.showSnackbar(it); vm.consumeToast() } }
    LaunchedEffect(openId) { if (openId != null) tab = Tab.Inbox }

    Box(Modifier.fillMaxSize().background(Ab.Ink)) {
        if (!settings.onboarded) {
            SetupScreen(vm) { vm.updateSettings { it.copy(onboarded = true) } }
        } else {
            AnimatedContent(tab, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "tab") { t ->
                when (t) {
                    Tab.Inbox -> InboxScreen(vm, openId, consumedOpen) { tab = Tab.Settings }
                    Tab.Greeting -> GreetingScreen(vm)
                    Tab.Settings -> SettingsScreen(vm)
                }
            }
            NavBar(tab, Modifier.align(Alignment.BottomCenter)) { tab = it }
        }
        SnackbarHost(snack, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 88.dp))
    }
}

@Composable
private fun NavBar(current: Tab, modifier: Modifier, onSelect: (Tab) -> Unit) {
    Row(
        modifier
            .navigationBarsPadding()
            .padding(bottom = 14.dp)
            .clip(RoundedCornerShape(50))
            .background(Ab.Surface2.copy(alpha = 0.96f))
            .border(1.dp, Ab.Outline, RoundedCornerShape(50))
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Tab.entries.forEach { t ->
            val sel = t == current
            val icon = when (t) { Tab.Inbox -> Icons.Rounded.Inbox; Tab.Greeting -> Icons.Rounded.GraphicEq; Tab.Settings -> Icons.Rounded.Tune }
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (sel) Ab.BeepH else androidx.compose.ui.graphics.SolidColor(Color.Transparent))
                    .clickable { onSelect(t) }
                    .padding(horizontal = if (sel) 18.dp else 16.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(icon, t.label, tint = if (sel) Ab.Ink else Ab.Muted, modifier = Modifier.size(21.dp))
                if (sel) {
                    Spacer(Modifier.width(8.dp))
                    Text(t.label, style = MaterialTheme.typography.labelLarge, color = Ab.Ink)
                }
            }
        }
    }
}
