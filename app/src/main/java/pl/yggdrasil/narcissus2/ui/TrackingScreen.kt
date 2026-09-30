package pl.yggdrasil.narcissus2.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pl.yggdrasil.narcissus2.domain.ActivityMode
import pl.yggdrasil.narcissus2.i18n.Lang
import pl.yggdrasil.narcissus2.i18n.tr
import pl.yggdrasil.narcissus2.ui.components.BurnInShift
import pl.yggdrasil.narcissus2.ui.components.Command
import pl.yggdrasil.narcissus2.ui.components.IndicatorStrip
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
 * Bierze listę metryk z [ActivityMode]. W trybach z [ActivityMode.hero]
 * pierwsza metryka idzie na wielką cyfrę, reszta — po jednej w wierszu.
 * Zmiana zestawu
 * odczytów to zmiana listy w enumie, nie zmiana tego pliku.
 */
@Composable
fun TrackingScreen(
    state: TrackingUiState,
    onCommence: () -> Unit,
    onTerminate: () -> Unit,
    onMode: (ActivityMode) -> Unit,
    onTogglePosition: () -> Unit,
    onArchive: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = Theme.palette
    val metrics = state.mode.metrics
    val hero = state.mode.hero

    // Ekran świeci bez przerwy, a układ stoi w miejscu — na AMOLED-zie to
    // przepis na wypalenie etykiet. Treść powoli wędruje po matrycy.
    val shift: BurnInShift = rememberBurnInShift()

    Column(
        modifier
            .fillMaxSize()
            .background(p.void)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .burnInPadding(horizontal = 16.dp, vertical = 16.dp, shift = shift),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {

        // Wszystko nad przyciskiem przewija się. Rozwinięty panel pozycji
        // w trybie z pięcioma metrykami nie mieści się na ekranie — bez
        // przewijania wypychał START poza krawędź.
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // --- nagłówek ---
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // Tryb widać w selektorze niżej; w nagłówku "PIESZO" + "DZIENNIK"
                // nie mieściło się w jednej linii.
                // Przełącznik języka przyklejony do stałego "NARCISSUS" po lewej.
                // Między nim a DZIENNIK/LOG pływał, bo te mają różną długość.
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Label("NARCISSUS", color = p.dim, softWrap = false)

                    if (!state.active) {
                        // Pokazuje BIEŻĄCY język; dotknięcie przełącza.
                        Label(
                            if (Lang.polish) "[PL]" else "[EN]",
                            color = p.dim,
                            softWrap = false,
                            modifier = Modifier.tap(Lang::toggle),
                        )
                    }
                }

                if (!state.active) {
                    Label(
                        tr("LOG", "DZIENNIK"),
                        color = p.phosphor,
                        softWrap = false,
                        modifier = Modifier.tap(onArchive),
                    )
                }
            }

            // --- status ---
            StatusLine(state)

            state.notice?.let { notice ->
                Label(notice, color = p.amber, softWrap = false)
            }

            ModeSelector(
                labels = ActivityMode.entries.map { it.label.text },
                selectedIndex = state.mode.ordinal,
                enabled = !state.active,
                onSelect = { onMode(ActivityMode.entries[it]) },
            )

            // --- wskaźniki podsystemów, w pasku ---
            Panel {
                IndicatorStrip(status = state.system, onTapGnss = onTogglePosition)
            }

            PositionPanel(
                visible = state.positionVisible,
                latitude = state.latitude,
                longitude = state.longitude,
                accuracyM = state.telemetry.lastAccuracyM,
                status = state.system,
                onDismiss = onTogglePosition,
            )

            // --- wielka cyfra, tylko w trybach z hero ---
            if (hero) {
                Panel(alignment = Alignment.End) {
                    Readout(
                        metric = metrics[0],
                        telemetry = state.telemetry,
                        valueSize = Grid.READOUT,
                    )
                }
            }

            // --- reszta: po jednej metryce w wierszu ---
            // Dwie w rzędzie sklejały się, gdy czas dobijał do godzin.
            Panel(alignment = Alignment.End) {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    metrics.drop(if (hero) 1 else 0).forEach { metric ->
                        Readout(metric, state.telemetry, valueSize = Grid.ROW)
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
            tr("READY - %.0f M", "GOTOWOŚĆ - %.0f M").format(acc ?: 0f) to p.phosphor

        // Fix jest, ale za słaby dla progu tego trybu.
        !state.active && state.system.gnss.hasFix ->
            tr("WEAK SIGNAL - %.0f M", "SYGNAŁ SŁABY - %.0f M").format(acc ?: 0f) to p.amber

        !state.active -> tr("ACQUIRING POSITION", "POZYSKIWANIE POZYCJI") to p.amber

        state.telemetry.warmingUp -> tr("CALIBRATING - %.0f M", "KALIBRACJA - %.0f M").format(acc ?: 0f) to p.amber

        acc != null && acc > state.mode.thresholds.maxAccuracyM ->
            tr("WEAK SIGNAL - %.0f M", "SYGNAŁ SŁABY - %.0f M").format(acc) to p.amber

        !state.telemetry.moving -> tr("STOPPED - %.0f M", "POSTÓJ - %.0f M").format(acc ?: 0f) to p.amber

        else -> tr("UNDERWAY - %.0f M", "W DRODZE - %.0f M").format(acc ?: 0f) to p.phosphor
    }

    Label(text, color = color, softWrap = false)
}
