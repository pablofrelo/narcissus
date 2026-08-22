package pl.yggdrasil.narcissus2.ui.components

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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import pl.yggdrasil.narcissus2.domain.Metric
import pl.yggdrasil.narcissus2.domain.Telemetry
import pl.yggdrasil.narcissus2.ui.theme.Grid
import pl.yggdrasil.narcissus2.ui.theme.Theme
import pl.yggdrasil.narcissus2.ui.theme.Type

/** Klik bez ripple'a. Ripple w tej estetyce wygląda jak wpadka. */
@Composable
fun Modifier.tap(onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    return this.clickable(
        interactionSource = source,
        indication = null,
        onClick = onClick,
    )
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
        fontSize = size.sp,
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
            .padding(horizontal = 10.dp, vertical = 8.dp),
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
        Label(metric.label, color = p.phosphor, softWrap = false)

        Text(
            text = metric.read(telemetry),
            color = p.readout,
            fontFamily = Type.Readout,
            fontSize = valueSize.sp,
            maxLines = 1,
        )

        if (metric.unit.isNotEmpty()) {
            Label(metric.unit, color = p.dim, size = Grid.UNIT, softWrap = false)
        }
    }
}

/**
 * Przycisk komendy wymagający PRZYTRZYMANIA.
 *
 * Zwykłe dotknięcie odpada: telefon jedzie w uchwycie na kierownicy, a
 * przypadkowe muśnięcie kończące przejazd w połowie trasy to strata, której
 * nie da się odzyskać. Dwie sekundy to za długo na przypadek i za krótko,
 * żeby denerwowało.
 *
 * Postęp wypełnia przycisk od lewej. Bez tego przytrzymanie jest irytujące,
 * bo nie wiadomo, ile jeszcze — a puszczenie sekundę za wcześnie nie daje
 * żadnej informacji zwrotnej.
 */
@Composable
fun Command(
    text: String,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    holdMs: Long = 2_000L,
) {
    val p = Theme.palette
    var progress by remember { mutableFloatStateOf(0f) }
    var pressed by remember { mutableStateOf(false) }

    LaunchedEffect(pressed) {
        if (!pressed) {
            // Odjazd do zera jest szybszy niż narastanie — puszczenie
            // przycisku ma być natychmiast widoczne.
            while (progress > 0f) {
                progress = (progress - 0.08f).coerceAtLeast(0f)
                delay(16)
            }
            return@LaunchedEffect
        }

        val startedAt = withFrameMillis { it }
        while (progress < 1f) {
            val elapsed = withFrameMillis { it } - startedAt
            progress = (elapsed.toFloat() / holdMs).coerceIn(0f, 1f)
        }

        pressed = false
        progress = 0f
        onClick()
    }

    Box(
        modifier
            .fillMaxWidth()
            .height(66.dp)
            .border(1.dp, color)
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        pressed = true
                        // Czeka na puszczenie albo anulowanie gestu.
                        tryAwaitRelease()
                        pressed = false
                    },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        // Wypełnienie postępu pod tekstem.
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress)
                .background(color.copy(alpha = 0.22f))
                .align(Alignment.CenterStart),
        )

        Text(
            text = text,
            color = color,
            fontFamily = Type.Chrome,
            fontSize = Grid.COMMAND.sp,
            letterSpacing = 7.sp,
        )

        if (progress > 0.02f) {
            Label(
                "PRZYTRZYMAJ",
                color = p.dim,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 6.dp),
                softWrap = false,
            )
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
                    .padding(vertical = 8.dp),
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
