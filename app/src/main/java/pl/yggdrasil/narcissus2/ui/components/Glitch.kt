package pl.yggdrasil.narcissus2.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Zakłócenie jak na starym monitorze: co 45–90 s na dwie sekundy kilka
 * poziomych pasów obrazu szarpie w bok, a przez ekran przejeżdża jasny pas.
 *
 * To OZDOBA, nie ochrona matrycy — dwie sekundy na minutę nie zmieniają tego,
 * ile świeci każdy piksel. Przed wypaleniem chroni [burnInPadding].
 */
@Composable
fun Modifier.crtGlitch(tint: Color): Modifier {
    val progress = remember { Animatable(0f) }
    var seed by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(Random.nextLong(45_000, 90_000))
            seed = Random.nextInt()
            progress.snapTo(0f)
            progress.animateTo(1f, tween(DURATION_MS, easing = LinearEasing))
            progress.snapTo(0f)
        }
    }

    return this.drawWithContent {
        val p = progress.value
        if (p == 0f) {
            drawContent()
            return@drawWithContent
        }

        // Siła rośnie i opada w trakcie; układ pasów zmienia się kilka razy
        // na przebieg, więc obraz "szarpie", a nie przesuwa się płynnie.
        val strength = sin(p * PI).toFloat()
        val rnd = Random(seed + (p * JUMPS).toInt())
        val maxShift = 14.dp.toPx() * strength

        // Najpierw cały obraz, potem kilka pasów przerysowanych z przesunięciem.
        drawContent()
        repeat(BANDS) {
            val h = size.height * rnd.nextFloat() * 0.06f + 2.dp.toPx()
            val top = rnd.nextFloat() * (size.height - h)
            val dx = (rnd.nextFloat() * 2f - 1f) * maxShift
            clipRect(top = top, bottom = top + h) {
                drawRect(Color.Black, topLeft = Offset(0f, top), size = Size(size.width, h))
                translate(left = dx) { this@drawWithContent.drawContent() }
            }
        }

        // Jasny pas przejeżdżający z góry na dół.
        val band = 48.dp.toPx()
        val y = -band + (size.height + 2 * band) * p
        drawRect(
            brush = Brush.verticalGradient(
                listOf(Color.Transparent, tint.copy(alpha = 0.12f * strength), Color.Transparent),
                startY = y - band,
                endY = y + band,
            ),
            topLeft = Offset(0f, y - band),
            size = Size(size.width, 2 * band),
        )
    }
}

private const val DURATION_MS = 2_000
private const val BANDS = 5

/** Ile razy w trakcie jednego zakłócenia pasy zmieniają położenie. */
private const val JUMPS = 24
