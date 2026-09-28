package pl.yggdrasil.narcissus2.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pl.yggdrasil.narcissus2.domain.ActivityMode
import pl.yggdrasil.narcissus2.ui.components.BurnInShift
import pl.yggdrasil.narcissus2.ui.components.Command
import pl.yggdrasil.narcissus2.ui.components.IndicatorRail
import pl.yggdrasil.narcissus2.ui.components.Label
import pl.yggdrasil.narcissus2.ui.components.ModeSelector
import pl.yggdrasil.narcissus2.ui.components.Panel
import pl.yggdrasil.narcissus2.ui.components.PositionPanel
import pl.yggdrasil.narcissus2.ui.components.Readout
import pl.yggdrasil.narcissus2.ui.components.burnInPadding
import pl.yggdrasil.narcissus2.ui.components.rememberBurnInShift
import pl.yggdrasil.narcissus2.ui.components.tap
import pl.yggdrasil.narcissus2.ui.theme.Grid
import pl.yggdrasil.narcissus2.ui.theme.Theme

/**
 * Ekran nie wie nic o trybach.
 *
 * Bierze listę metryk z [ActivityMode], kładzie pierwszą na duży wyświetlacz,
 * a resztę stawia w kolumnie pod spodem. Zmiana zestawu odczytów to zmiana
 * listy w enumie, nie zmiana tego pliku.
 *
 * Układ: górny panel to sam duży odczyt na pełną szerokość. Dolny dzieli się
 * na wskaźniki podsystemów po lewej i kolumnę pozostałych odczytów po prawej.
 * Wszystkie odczyty — duży i małe — używają tego samego wzoru [Readout],
 * więc oko nie musi przełączać się między dwoma układami.
 */
@Composable
fun TrackingScreen(
    state: TrackingUiState,
    onCommence: () -> Unit,
    onTerminate: () -> Unit,
    onMode: (ActivityMode) -> Unit,
    onToggleDay: () -> Unit,
    onTogglePosition: () -> Unit,
    onArchive: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = Theme.palette
    val metrics = state.mode.metrics

    // Ekran świeci bez przerwy, a układ stoi w miejscu — na AMOLED-zie to
    // przepis na wypalenie etykiet. Treść powoli wędruje po matrycy.
    val shift: BurnInShift = rememberBurnInShift()

    Column(
        modifier
            .fillMaxSize()
            .background(p.void)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .burnInPadding(horizontal = 12.dp, vertical = 8.dp, shift = shift),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {

        // --- nagłówek ---
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Label("NARCISSUS // ${state.mode.label}", color = p.dim, softWrap = false)

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Label(
                    if (state.day) "[DZIEŃ]" else "[NOC]",
                    color = p.phosphor,
                    softWrap = false,
                    modifier = Modifier.tap(onToggleDay),
                )

                if (!state.active) {
                    Label(
                        "DZIENNIK",
                        color = p.phosphor,
                        softWrap = false,
                        modifier = Modifier.tap(onArchive),
                    )
                }
            }
        }

        StatusLine(state)

        state.notice?.let { notice ->
            Label(notice, color = p.amber, softWrap = false)
        }

        ModeSelector(
            labels = ActivityMode.entries.map { it.label },
            selectedIndex = state.mode.ordinal,
            enabled = !state.active,
            onSelect = { onMode(ActivityMode.entries[it]) },
        )

        PositionPanel(
            visible = state.positionVisible,
            latitude = state.latitude,
            longitude = state.longitude,
            accuracyM = state.telemetry.lastAccuracyM,
            status = state.system,
            onDismiss = onTogglePosition,
        )

        // --- panel górny: sam duży odczyt, pełna szerokość ---
        // Tylko w trybach z [ActivityMode.hero]. W biegu telefon jest w ręce,
        // więc wszystkie odczyty idą do jednej kolumny, jednym rozmiarem.
        if (state.mode.hero) {
            Panel(alignment = Alignment.End) {
                Readout(
                    metric = metrics[0],
                    telemetry = state.telemetry,
                    valueSize = Grid.READOUT,
                )
            }
        }

        // --- panel dolny: wskaźniki po lewej, odczyty po prawej ---
        Panel(Modifier.weight(1f)) {
            Row(Modifier.fillMaxHeight()) {

                IndicatorRail(
                    status = state.system,
                    onTapGnss = onTogglePosition,
                    modifier = Modifier.fillMaxHeight(),
                )

                Spacer(Modifier.width(12.dp))

                // Rozłożone na całą wysokość panelu — to właśnie miejsce,
                // które wcześniej stało puste u dołu ekranu.
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.SpaceEvenly,
                ) {
                    metrics.drop(if (state.mode.hero) 1 else 0).forEach { metric ->
                        Readout(metric, state.telemetry)
                    }
                }
            }
        }

        if (state.active) {
            Command("STOP", p.alarm, onTerminate)
        } else {
            Command("START", p.phosphor, onCommence)
        }
    }
}

@Composable
private fun StatusLine(state: TrackingUiState) {
    val p = Theme.palette
    val acc = state.telemetry.lastAccuracyM

    val (text, color) = when {
        // Czuwanie z pełnowartościowym fixem — można ruszać.
        !state.active && state.ready ->
            "GOTOWOŚĆ - %.0f M".format(acc ?: 0f) to p.phosphor

        // Fix jest, ale za słaby dla progu tego trybu.
        !state.active && state.system.gnss.hasFix ->
            "SYGNAŁ SŁABY - %.0f M".format(acc ?: 0f) to p.amber

        !state.active -> "POZYSKIWANIE POZYCJI" to p.amber

        state.telemetry.warmingUp -> "KALIBRACJA - %.0f M".format(acc ?: 0f) to p.amber

        acc != null && acc > state.mode.thresholds.maxAccuracyM ->
            "SYGNAŁ SŁABY - %.0f M".format(acc) to p.amber

        !state.telemetry.moving -> "POSTÓJ - %.0f M".format(acc ?: 0f) to p.amber

        else -> "W DRODZE - %.0f M".format(acc ?: 0f) to p.phosphor
    }

    Label(text, color = color, softWrap = false, modifier = Modifier.height(16.dp))
}
