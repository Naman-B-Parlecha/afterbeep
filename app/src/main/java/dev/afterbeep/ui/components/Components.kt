package dev.afterbeep.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Phone
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.afterbeep.ui.theme.Ab
import kotlin.math.abs

/** "afterbeep" wordmark with a gradient beep dot. */
@Composable
fun Wordmark(modifier: Modifier = Modifier, size: Int = 30) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(color = Ab.Text)) { append("after") }
                withStyle(SpanStyle(color = Ab.Muted)) { append("beep") }
            },
            fontSize = size.sp, fontWeight = FontWeight.Black, letterSpacing = (-size * 0.045).sp,
        )
        Spacer(Modifier.width((size * 0.12).dp))
        Box(Modifier.padding(top = (size * 0.34).dp).size((size * 0.3).dp).clip(CircleShape).background(Ab.Beep))
    }
}

/** Live engine indicator: pulsing dot + label. */
@Composable
fun StatusPill(label: String, color: Color, pulsing: Boolean, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val t = rememberInfiniteTransition(label = "pulse")
    val scale by t.animateFloat(1f, 2.4f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing)), label = "s")
    val alpha by t.animateFloat(0.55f, 0f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing)), label = "a")
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.25f), RoundedCornerShape(50))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(10.dp)) {
            if (pulsing) Box(
                Modifier.size(8.dp).graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha }
                    .clip(CircleShape).background(color)
            )
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        }
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

@Composable
fun Avatar(name: String?, seed: String, size: Dp = 46.dp) {
    val (a, b) = Ab.avatarPairs[abs(seed.hashCode()) % Ab.avatarPairs.size]
    Box(
        Modifier.size(size).clip(CircleShape).background(Brush.linearGradient(listOf(a, b))),
        contentAlignment = Alignment.Center,
    ) {
        val initials = name?.split(" ")?.filter { it.isNotBlank() }?.take(2)?.joinToString("") { it.first().uppercase() }
        if (!initials.isNullOrBlank() && initials.first().isLetter()) {
            Text(initials, color = Ab.Ink, fontWeight = FontWeight.Black, fontSize = (size.value * 0.36).sp)
        } else {
            Icon(Icons.Rounded.Phone, null, tint = Ab.Ink, modifier = Modifier.size(size * 0.44f))
        }
    }
}

/** Bar waveform; played part in the beep gradient. Tap / drag to seek. */
@Composable
fun Waveform(
    bars: FloatArray,
    progress: Float,
    modifier: Modifier = Modifier,
    height: Dp = 44.dp,
    onSeek: ((Float) -> Unit)? = null,
) {
    val seek = if (onSeek != null) Modifier
        .pointerInput(Unit) { detectTapGestures { onSeek((it.x / size.width).coerceIn(0f, 1f)) } }
        .pointerInput(Unit) { detectHorizontalDragGestures { ch, _ -> onSeek((ch.position.x / size.width).coerceIn(0f, 1f)) } }
    else Modifier
    Canvas(modifier.fillMaxWidth().height(height).then(seek)) {
        if (bars.isEmpty()) return@Canvas
        val gap = 3.dp.toPx()
        val w = ((size.width - gap * (bars.size - 1)) / bars.size).coerceAtLeast(1.5f)
        val brush = Brush.horizontalGradient(listOf(Ab.Amber, Ab.Coral), 0f, size.width)
        bars.forEachIndexed { i, v ->
            val x = i * (w + gap)
            val h = (v * size.height).coerceAtLeast(w)
            val played = (x + w / 2) / size.width <= progress
            val tl = Offset(x, (size.height - h) / 2)
            if (played) drawRoundRect(brush, tl, Size(w, h), CornerRadius(w / 2))
            else drawRoundRect(Ab.Faint, tl, Size(w, h), CornerRadius(w / 2))
        }
    }
}

@Composable
fun PlayButton(playing: Boolean, size: Dp = 48.dp, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(CircleShape).background(Ab.Beep).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = Ab.Ink, modifier = Modifier.size(size * 0.55f))
    }
}

@Composable
fun Card(modifier: Modifier = Modifier, padding: Dp = 18.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(Ab.Surface)
            .border(1.dp, Ab.Outline.copy(alpha = 0.6f), RoundedCornerShape(26.dp))
            .padding(padding),
        content = content,
    )
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) =
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = Ab.Faint, modifier = modifier.padding(start = 6.dp, bottom = 8.dp))

/** Gradient pill button. */
@Composable
fun BeepButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, leading: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(if (enabled) Ab.BeepH else Brush.horizontalGradient(listOf(Ab.Surface3, Ab.Surface3)))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        leading?.invoke()
        if (leading != null) Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (enabled) Ab.Ink else Ab.Faint)
    }
}

@Composable
fun GhostButton(text: String, modifier: Modifier = Modifier, color: Color = Ab.Text, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .border(1.dp, Ab.Outline, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = MaterialTheme.typography.labelLarge, color = color) }
}

/** Concentric "sound rings" used for empty states and the setup hero. */
@Composable
fun Rings(modifier: Modifier = Modifier, size: Dp = 160.dp, center: @Composable () -> Unit) {
    val t = rememberInfiniteTransition(label = "rings")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart), label = "p")
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val max = this.size.minDimension / 2
            for (k in 0 until 3) {
                val f = ((p + k / 3f) % 1f)
                drawCircle(Ab.Orange.copy(alpha = (1 - f) * 0.35f), radius = max * (0.35f + 0.65f * f), style = Stroke(1.5.dp.toPx()))
            }
        }
        Box(
            Modifier.size(size * 0.42f).clip(CircleShape).background(Ab.Surface2)
                .drawBehind { drawCircle(Brush.linearGradient(listOf(Ab.Amber, Ab.Coral)), style = Stroke(2.dp.toPx())) },
            contentAlignment = Alignment.Center,
        ) { center() }
    }
}

fun formatDuration(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}
