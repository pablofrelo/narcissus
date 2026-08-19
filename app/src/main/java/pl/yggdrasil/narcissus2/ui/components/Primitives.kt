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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

/** Przycisk komendy: pusty prostokąt, kolor niesie znaczenie. */
@Composable
fun Command(
    text: String,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxWidth()
            .height(66.dp)
            .border(1.dp, color)
            .tap(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = color,
            fontFamily = Type.Chrome,
            fontSize = Grid.COMMAND.sp,
            letterSpacing = 7.sp,
        )
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
