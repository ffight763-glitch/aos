package pro.translate.app.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin

val RecRed = Color(0xFFE5484D)

/**
 * Live voice waves (as in the AOS translator): layered translucent waves rise from the bottom
 * while the user speaks and follow the loudness [level] (0..1). Doesn't take touches.
 */
@Composable
fun VoiceWaves(active: Boolean, level: Float, color: Color, modifier: Modifier = Modifier) {
    val alpha by animateFloatAsState(if (active) 1f else 0f, tween(if (active) 250 else 350), label = "waves")
    if (alpha == 0f) return
    var amp by remember { mutableFloatStateOf(0.15f) }
    var target by remember { mutableFloatStateOf(0.15f) }
    var phase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(level) { target = maxOf(target, level.coerceIn(0.12f, 1f)) }
    LaunchedEffect(Unit) {
        while (true) withFrameNanos {
            amp += (target - amp) * 0.18f        // follow the voice smoothly
            target += (0.15f - target) * 0.04f   // and settle back while it is quiet
            phase += 0.06f
        }
    }
    val colors = remember(color) { listOf(color, lerp(color, Color.White, 0.35f), color, lerp(color, Color.White, 0.6f)) }
    Canvas(modifier.graphicsLayer { this.alpha = alpha }) {
        val w = size.width; val h = size.height
        if (w <= 0f) return@Canvas
        val ph = phase; val a = amp
        for (i in 0 until 4) {
            val base = h * (0.55f + i * 0.11f) - h * 0.35f * a * (1f - i * 0.18f)
            val hh = h * 0.12f * (0.4f + a) * (1f - i * 0.15f)
            val path = Path().apply {
                moveTo(0f, h); lineTo(0f, base)
                var x = 0f
                while (x <= w) {
                    val t = x / w * 2 * PI
                    val y = base + (sin(t * (1.2 + i * 0.35) + ph * (1f + i * 0.4f) + i) * hh +
                        sin(t * 3.1 - ph * 1.3 + i * 2) * hh * 0.35).toFloat()
                    lineTo(x, y); x += 10f
                }
                lineTo(w, h); close()
            }
            drawPath(path, colors[i].copy(alpha = (70 + i * 30) / 255f))
        }
    }
}

/**
 * Mic button with the AOS listening animation: rings spread out while it records,
 * a quick bounce whenever new words arrive ([bump] changes), red stop state.
 */
@Composable
fun MicButton(
    listening: Boolean, ready: Boolean, level: Float, bump: Any?,
    color: Color, onClick: () -> Unit, size: Dp = 60.dp, modifier: Modifier = Modifier,
) {
    val inf = rememberInfiniteTransition(label = "rings")
    val t by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(1500, easing = LinearEasing)), label = "t")
    val bounce = remember { Animatable(1f) }
    LaunchedEffect(bump) {
        if (listening && bump != null) { bounce.animateTo(1.18f, tween(90)); bounce.animateTo(1f, tween(160)) }
    }
    val bg by animateColorAsStateCompat(if (listening) RecRed else color)
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        if (listening && ready) Canvas(Modifier.matchParentSize()) {
            val r = this.size.minDimension / 2
            repeat(3) { i ->
                val tt = (t + i / 3f) % 1f
                drawCircle(RecRed.copy(alpha = (1f - tt) * 0.8f), radius = r * (1f + 0.9f * tt),
                    center = Offset(this.size.width / 2, this.size.height / 2), style = Stroke(2.dp.toPx()))
            }
        }
        Box(
            Modifier.size(size).scale(bounce.value * (1f + if (listening) level * 0.08f else 0f))
                .clip(CircleShape).graphicsLayer { }.clickable(onClick = onClick)
                .then(Modifier), contentAlignment = Alignment.Center
        ) {
            Canvas(Modifier.matchParentSize()) { drawCircle(bg) }
            if (listening && !ready) CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
            else Icon(if (listening) Icons.Default.Stop else Icons.Default.Mic, if (listening) "Стоп" else "Говорить", tint = Color.White)
        }
    }
}

@Composable
private fun animateColorAsStateCompat(c: Color): State<Color> =
    androidx.compose.animation.animateColorAsState(c, tween(200), label = "mic")

/** Red "■ Стоп 0:07" pill shown while recording (as in AOS); tap = stop and translate. */
@Composable
fun StopBar(onClick: () -> Unit, modifier: Modifier = Modifier) {
    var sec by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { delay(1000); sec++ } }
    Box(modifier.height(56.dp).clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick)
        .then(Modifier), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) { drawRect(RecRed) }
        Text("■  Стоп   %d:%02d".format(sec / 60, sec % 60), color = Color.White, fontWeight = FontWeight.SemiBold)
    }
}
