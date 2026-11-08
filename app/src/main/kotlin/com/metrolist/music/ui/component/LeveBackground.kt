/**
 * Leve Music — fundo animado bem leve (tema "Console azul"):
 * algumas luzes suaves flutuando devagar e uma onda de luz discreta.
 * Desenho próprio, só ~20 círculos por quadro, sem imagens.
 */

package com.metrolist.music.ui.component

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

private data class Orb(val x: Float, val y: Float, val r: Float, val speed: Float, val phase: Float, val alpha: Float)

@Composable
fun LeveAnimatedBackground(
    accent: Color,
    modifier: Modifier = Modifier,
    style: String = "ps2",
) {
    val orbs =
        remember {
            val rnd = Random(7)
            List(20) {
                Orb(
                    x = rnd.nextFloat(),
                    y = rnd.nextFloat(),
                    r = 2f + rnd.nextFloat() * 5f,
                    speed = 0.3f + rnd.nextFloat() * 0.7f,
                    phase = rnd.nextFloat() * 2f * PI.toFloat(),
                    alpha = 0.15f + rnd.nextFloat() * 0.35f,
                )
            }
        }
    val transition = rememberInfiniteTransition(label = "leveBg")
    // Um ciclo longo e lento = pouco trabalho por quadro
    val t by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 40_000, easing = LinearEasing), RepeatMode.Restart),
        label = "t",
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        val angle0 = t * 2f * PI.toFloat()
        when (style) {
            "aurora" -> {
                // três faixas de luz ondulando no topo
                listOf(Color(0xFF1DE9B6), Color(0xFF7C4DFF), Color(0xFF2979FF)).forEachIndexed { i, c ->
                    val cx = size.width * (0.2f + 0.3f * i + 0.12f * sin(angle0 * 2 + i * 2.1f))
                    val cy = size.height * (0.18f + 0.05f * sin(angle0 * 3 + i))
                    drawOval(
                        Brush.radialGradient(
                            listOf(c.copy(alpha = 0.28f), Color.Transparent),
                            center = Offset(cx, cy),
                            radius = size.width * 0.55f,
                        ),
                        topLeft = Offset(cx - size.width * 0.7f, cy - size.height * 0.16f),
                        size = androidx.compose.ui.geometry.Size(size.width * 1.4f, size.height * 0.32f),
                    )
                }
                return@Canvas
            }
            "neve", "sakura" -> {
                orbs.forEachIndexed { i, o ->
                    val fall = (o.y + t * o.speed * 6f) % 1f
                    val cx = ((o.x + t * (if (style == "sakura") 0.8f else 0f) + sin(angle0 * 4 * o.speed + o.phase) * 0.03f) % 1f) * size.width
                    val cy = fall * size.height
                    if (style == "neve") {
                        drawCircle(Color.White.copy(alpha = 0.35f + o.alpha), radius = o.r * 0.9f, center = Offset(cx, cy))
                    } else {
                        val c = if (i % 3 == 0) Color(0xFFFFC1D9) else accent
                        drawOval(
                            c.copy(alpha = 0.55f + o.alpha * 0.5f),
                            topLeft = Offset(cx - o.r * 1.6f, cy - o.r * 0.9f),
                            size = androidx.compose.ui.geometry.Size(o.r * 3.2f, o.r * 1.8f),
                        )
                    }
                }
                return@Canvas
            }
            "vagalumes" -> {
                orbs.forEach { o ->
                    val cx = (o.x + sin(angle0 * 2 * o.speed + o.phase) * 0.05f) * size.width
                    val cy = (o.y + sin(angle0 * 1.5f * o.speed + o.phase * 1.7f) * 0.05f) * size.height
                    val pulse = 0.5f + 0.5f * sin(angle0 * 8 * o.speed + o.phase)
                    drawCircle(accent.copy(alpha = 0.12f * pulse), radius = o.r * 5f, center = Offset(cx, cy))
                    drawCircle(accent.copy(alpha = 0.4f + 0.6f * pulse), radius = o.r * 0.8f, center = Offset(cx, cy))
                }
                return@Canvas
            }
        }
        // Brilho de fundo
        drawRect(
            Brush.radialGradient(
                colors = listOf(accent.copy(alpha = 0.16f), Color.Transparent),
                center = Offset(size.width * 0.8f, size.height * 0.1f),
                radius = size.maxDimension * 0.7f,
            ),
        )
        // Onda de luz discreta
        val wave = Path()
        val baseY = size.height * 0.32f
        val angle = t * 2f * PI.toFloat()
        var x = 0f
        wave.moveTo(0f, baseY)
        while (x <= size.width) {
            val y = baseY + sin(x / size.width * 4f * PI.toFloat() + angle) * size.height * 0.03f
            wave.lineTo(x, y)
            x += 24f
        }
        drawPath(wave, accent.copy(alpha = 0.12f), style = Stroke(width = 3f))
        // Luzes flutuando
        orbs.forEach { o ->
            val drift = (o.y - t * o.speed + 1f) % 1f
            val cx = (o.x + sin(angle * o.speed + o.phase) * 0.03f) * size.width
            val cy = drift * size.height
            drawCircle(accent.copy(alpha = o.alpha * 0.35f), radius = o.r * 3.2f, center = Offset(cx, cy))
            drawCircle(Color.White.copy(alpha = o.alpha), radius = o.r, center = Offset(cx, cy))
        }
    }
}
