package pl.yggdrasil.narcissus2.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import pl.yggdrasil.narcissus2.domain.Metric
import pl.yggdrasil.narcissus2.domain.Telemetry
import pl.yggdrasil.narcissus2.i18n.tr
import pl.yggdrasil.narcissus2.ui.theme.Grid
import pl.yggdrasil.narcissus2.ui.theme.Theme
import pl.yggdrasil.narcissus2.ui.theme.Type
import pl.yggdrasil.narcissus2.ui.theme.gridSp

/**
 * Dotknięcie bez ripple'a, za to z krótką wibracją (jak w fazie 1).
 *
 * W ruchu patrzysz na drogę, nie na ekran — tknięcie w palec jest jedynym
 * potwierdzeniem, że trafiłeś. TextHandleMove to najkrótszy impuls, jaki
 * system oferuje; mocniejszy zostaje dla przycisków pod klapką.
 */
@Composable
fun Modifier.tap(onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    val haptic = LocalHapticFeedback.current
    return this.clickable(
        interactionSource = source,
        indication = null,
    ) {
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        onClick()
    }
}

@Composable
fun Label(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Theme.palette.phosphor,
    size: Int = Grid.LABEL,
    softWrap: Boolean = true,
) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        fontFamily = Type.Chrome,
        fontSize = gridSp(size),
        letterSpacing = 1.sp,
        softWrap = softWrap,
        maxLines = if (softWrap) Int.MAX_VALUE else 1,
    )
}

/** Panel: cienka obwódka i tło o krok inne niż tło ekranu. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    alignment: Alignment.Horizontal = Alignment.Start,
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = Theme.palette

    Column(
        modifier
            .fillMaxWidth()
            .background(p.hull)
            .border(1.dp, p.grid)
            .padding(5.dp),
        horizontalAlignment = alignment,
        content = content,
    )
}

/**
 * JEDYNY wzór odczytu w całej aplikacji: etykieta nad wartością nad
 * jednostką, wszystko wyrównane do prawej.
 *
 * Duży wskaźnik i małe odczyty różnią się wyłącznie rozmiarem [valueSize] —
 * ten sam układ, ta sama hierarchia, ta sama krawędź odniesienia. Dwa różne
 * wzory na jednym ekranie zmuszałyby oko do przełączania się między nimi.
 *
 * Wyrównanie do prawej nie jest ozdobą: cyfry o stałej szerokości wyrównane
 * do prawej nie skaczą, gdy wartość przekroczy dziesiątkę.
 */
@Composable
fun Readout(
    metric: Metric,
    telemetry: Telemetry,
    modifier: Modifier = Modifier,
    valueSize: Int = Grid.VALUE,
) {
    val p = Theme.palette

    Column(modifier, horizontalAlignment = Alignment.End) {
        Label(metric.labelOf?.invoke(telemetry) ?: metric.label.text, color = p.phosphor, softWrap = false)

        Text(
            text = metric.read(telemetry),
            color = p.readout,
            fontFamily = Type.forSize(valueSize),
            fontSize = gridSp(valueSize),
            maxLines = 1,
            softWrap = false,
        )

        if (metric.unit.text.isNotEmpty()) {
            Label(metric.unit.text, color = p.dim, size = Grid.UNIT, softWrap = false)
        }
    }
}

/**
 * Przycisk komendy pod klapką bezpieczeństwa (wzór z fazy 1).
 *
 * Zakreskowany jak osłona nad przełącznikiem uzbrojenia. Wymaga
 * PRZYTRZYMANIA: telefon jest w uchwycie albo w ręce w biegu, a przypadkowe
 * muśnięcie kończące trening w połowie to strata nie do odzyskania. Dwie
 * sekundy to za długo na przypadek i za krótko, żeby denerwowało.
 *
 * Postęp wypełnia przycisk od lewej, kreskowanie przy tym gaśnie. Mocna
 * wibracja na początku i na końcu — w ruchu to jedyne potwierdzenie.
 */
@Composable
fun Command(
    text: String,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    holdMs: Int = 2_000,
) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    // pointerInput żyje dłużej niż jedna kompozycja — bez tego wywołałby
    // nieaktualną wersję onClick.
    val action by rememberUpdatedState(onClick)
    val haptic = LocalHapticFeedback.current

    Box(
        modifier
            .fillMaxWidth()
            .height(84.dp)
            .border(1.dp, color)
            .pointerInput(holdMs) {
                detectTapGestures(
                    onPress = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        val run = scope.launch {
                            progress.animateTo(1f, tween(holdMs, easing = LinearEasing))
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            progress.snapTo(0f)
                            action()
                        }
                        tryAwaitRelease()
                        if (progress.value < 1f) {
                            run.cancel()
                            scope.launch { progress.animateTo(0f, tween(200)) }
                        }
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val p = progress.value

            drawRect(
                color = color.copy(alpha = 0.22f),
                size = Size(size.width * p, size.height),
            )

            val hatchAlpha = 0.30f * (1f - p)
            if (hatchAlpha > 0.01f) {
                val step = 16.dp.toPx()
                var x = -size.height
                while (x < size.width + size.height) {
                    drawLine(
                        color = color.copy(alpha = hatchAlpha),
                        start = Offset(x, size.height),
                        end = Offset(x + size.height, 0f),
                        strokeWidth = 2f,
                    )
                    x += step
                }
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = text,
                color = color,
                fontFamily = Type.Chrome,
                fontSize = gridSp(Grid.COMMAND),
                letterSpacing = 6.sp,
            )
            Label(tr("HOLD", "PRZYTRZYMAJ"), color = color.copy(alpha = 0.6f), softWrap = false)
        }
    }
}

/** Selektor trybu. Nieaktywny w trakcie przejazdu, ale zawsze widoczny. */
@Composable
fun ModeSelector(
    labels: List<String>,
    selectedIndex: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = Theme.palette

    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        labels.forEachIndexed { i, label ->
            val chosen = i == selectedIndex

            val edge = when {
                chosen -> p.phosphor
                enabled -> p.grid
                else -> p.hull
            }

            Box(
                Modifier
                    .weight(1f)
                    .border(1.dp, edge)
                    .background(if (chosen) p.hull else p.void)
                    .then(if (enabled) Modifier.tap { onSelect(i) } else Modifier)
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Label(
                    label,
                    color = when {
                        chosen -> p.phosphor
                        enabled -> p.dim
                        else -> p.hull
                    },
                    softWrap = false,
                )
            }
        }
    }
}
