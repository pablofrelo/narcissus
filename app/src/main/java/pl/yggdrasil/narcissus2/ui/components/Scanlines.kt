package pl.yggdrasil.narcissus2.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Scanlines jak na pablofrelo.github.io: linia 1 na każde 3, czerń 28%.
 *
 * Grubość i odstęp zaokrąglone do pełnych pikseli — przy gęstości 2,625
 * (S20 FE) ułamkowe linie wychodziłyby nierówne. Ścieżka budowana raz
 * na rozmiar, nie w każdej klatce.
 */
fun Modifier.scanlines(): Modifier = drawWithCache {
    val h = 1.dp.toPx().roundToInt().coerceAtLeast(1)
    val step = 3.dp.toPx().roundToInt().coerceAtLeast(h + 1)
    val lines = Path().apply {
        var y = 0
        while (y < size.height) {
            addRect(Rect(0f, y.toFloat(), size.width, (y + h).toFloat()))
            y += step
        }
    }
    val shade = Color.Black.copy(alpha = 0.28f)
    onDrawWithContent {
        drawContent()
        drawPath(lines, shade)
    }
}
