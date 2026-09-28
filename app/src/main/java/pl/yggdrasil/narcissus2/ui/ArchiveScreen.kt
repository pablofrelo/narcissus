package pl.yggdrasil.narcissus2.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import pl.yggdrasil.narcissus2.domain.Session
import pl.yggdrasil.narcissus2.ui.components.Label
import pl.yggdrasil.narcissus2.ui.components.Panel
import pl.yggdrasil.narcissus2.ui.components.TrackView
import pl.yggdrasil.narcissus2.ui.components.tap
import pl.yggdrasil.narcissus2.ui.theme.Theme
import pl.yggdrasil.narcissus2.ui.theme.Type
import pl.yggdrasil.narcissus2.ui.theme.gridSp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DATE = DateTimeFormatter.ofPattern("dd.MM.yy HH:mm").withZone(ZoneId.systemDefault())

@Composable
fun ArchiveScreen(
    state: ArchiveUiState,
    onOpen: (Session) -> Unit,
    onClose: () -> Unit,
    onBack: () -> Unit,
    onAskDelete: (String) -> Unit,
    onCancelDelete: () -> Unit,
    onConfirmDelete: (String) -> Unit,
    onSync: () -> Unit,
    onDeleteTests: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = Theme.palette

    Column(
        modifier
            .fillMaxSize()
            .background(p.void)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val opened = state.opened

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Label(
                if (opened == null) "DZIENNIK" else "SESJA",
                color = p.dim,
                softWrap = false,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (opened == null) {
                    Label(
                        if (state.syncing) "[SYNC...]" else "[SYNC]",
                        color = if (state.syncing) p.amber else p.phosphor,
                        softWrap = false,
                        modifier = Modifier.tap { if (!state.syncing) onSync() },
                    )
                }

                Label(
                    "[POWRÓT]",
                    color = p.phosphor,
                    softWrap = false,
                    modifier = Modifier.tap { if (opened == null) onBack() else onClose() },
                )
            }
        }

        state.syncMessage?.let { msg ->
            Label(
                msg,
                color = if (msg.startsWith("BŁĄD")) p.alarm else p.phosphor,
                softWrap = false,
            )
        }

        if (opened == null) {
            SessionList(
                state,
                onOpen,
                onAskDelete,
                onCancelDelete,
                onConfirmDelete,
                onDeleteTests,
            )
        } else {
            SessionDetail(state, opened, onAskDelete, onCancelDelete, onConfirmDelete)
        }
    }
}

@Composable
private fun ColumnScope.SessionList(
    state: ArchiveUiState,
    onOpen: (Session) -> Unit,
    onAskDelete: (String) -> Unit,
    onCancelDelete: () -> Unit,
    onConfirmDelete: (String) -> Unit,
    onDeleteTests: () -> Unit,
) {
    val p = Theme.palette

    if (state.loading) {
        Label("WCZYTYWANIE...", color = p.dim)
        return
    }

    if (state.sessions.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Label("BRAK ZAPISANYCH SESJI", color = p.dim, softWrap = false)
        }
        return
    }

    // Nagłówek zbiorczy — suma tego, co w ogóle jest w dzienniku.
    val totalKm = state.sessions.sumOf { it.distanceM } / 1000.0
    val tests = state.sessions.count { it.test }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Label(
            "%d SESJI / %.1f KM".format(state.sessions.size, totalKm),
            color = p.phosphor,
            softWrap = false,
        )

        // Pojawia się tylko wtedy, gdy jest co sprzątać — pusty przycisk
        // do kasowania niczego byłby tylko okazją do pomyłki.
        if (tests > 0) {
            Label(
                "[USUŃ $tests TEST.]",
                color = p.amber,
                softWrap = false,
                modifier = Modifier.tap(onDeleteTests),
            )
        }
    }

    LazyColumn(
        Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(state.sessions, key = { it.id }) { session ->
            SessionRow(
                session = session,
                pendingDelete = state.pendingDelete == session.id,
                onOpen = { onOpen(session) },
                onAskDelete = { onAskDelete(session.id) },
                onCancelDelete = onCancelDelete,
                onConfirmDelete = { onConfirmDelete(session.id) },
            )
        }
    }
}

@Composable
private fun SessionRow(
    session: Session,
    pendingDelete: Boolean,
    onOpen: () -> Unit,
    onAskDelete: () -> Unit,
    onCancelDelete: () -> Unit,
    onConfirmDelete: () -> Unit,
) {
    val p = Theme.palette

    Column(
        Modifier
            .fillMaxWidth()
            .background(p.hull)
            .border(1.dp, if (pendingDelete) p.alarm else p.grid)
            .tap { if (pendingDelete) onCancelDelete() else onOpen() }
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Label(
                DATE.format(Instant.ofEpochMilli(session.startedAt)),
                color = p.dim,
                softWrap = false,
            )

            Label(
                if (session.test) "${session.mode.label} / TEST" else session.mode.label,
                color = if (session.test) p.amber else p.phosphor,
                softWrap = false,
            )
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = "%.2f KM".format(session.distanceM / 1000.0),
                color = p.readout,
                fontFamily = Type.Readout,
                fontSize = gridSp(22),
            )

            Label(clock(session.elapsedMs), color = p.phosphor, softWrap = false)
        }

        if (pendingDelete) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Label("DOTKNIJ BY ANULOWAĆ", color = p.dim, softWrap = false)

                Label(
                    "[USUŃ]",
                    color = p.alarm,
                    softWrap = false,
                    modifier = Modifier.tap(onConfirmDelete),
                )
            }
        } else {
            Label(
                "[X]",
                color = p.grid,
                softWrap = false,
                modifier = Modifier
                    .align(Alignment.End)
                    .tap(onAskDelete),
            )
        }
    }
}

@Composable
private fun ColumnScope.SessionDetail(
    state: ArchiveUiState,
    session: Session,
    onAskDelete: (String) -> Unit,
    onCancelDelete: () -> Unit,
    onConfirmDelete: (String) -> Unit,
) {
    val p = Theme.palette

    Label(
        DATE.format(Instant.ofEpochMilli(session.startedAt)) + "  //  " + session.mode.label,
        color = p.dim,
        softWrap = false,
    )

    Panel {
        if (state.trackLoading) {
            Label("WCZYTYWANIE ŚLADU...", color = p.dim)
        } else {
            TrackView(state.track)
        }
    }

    Panel(Modifier.weight(1f)) {
        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.SpaceEvenly,
        ) {
            Stat("DYSTANS", "%.2f".format(session.distanceM / 1000.0), "KM")
            Stat("CZAS", clock(session.elapsedMs), "")
            Stat("W RUCHU", clock(session.movingMs), "")
            Stat("ŚREDNIA", "%.1f".format(session.avgMovingSpeedMps * 3.6f), "KM/H")
            Stat("MAKS", "%.1f".format(session.maxSpeedMps * 3.6f), "KM/H")

            if (session.mode.usesStepSensor) {
                Stat("KROKI", session.totalSteps.toString(), "")
            }

            Stat("POMIARY", "${session.acceptedFixes}/${session.rejectedFixes}", "OK/REJ")
            Stat("PUNKTY", session.pointCount.toString(), "")

            Stat(
                "PRZEWYŻSZENIE",
                session.ascentM?.let { "%.0f".format(it) } ?: "----",
                "M",
            )
        }
    }

    // Stan synchronizacji — bez tego nie wiadomo, czy sesja jest już
    // bezpieczna na heimdallu, czy istnieje tylko na telefonie.
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Label(
            if (session.synced) "ZSYNCHRONIZOWANO" else "TYLKO LOKALNIE",
            color = if (session.synced) p.phosphor else p.amber,
            softWrap = false,
        )

        if (state.pendingDelete == session.id) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Label(
                    "[ANULUJ]",
                    color = p.dim,
                    softWrap = false,
                    modifier = Modifier.tap(onCancelDelete),
                )
                Label(
                    "[USUŃ]",
                    color = p.alarm,
                    softWrap = false,
                    modifier = Modifier.tap { onConfirmDelete(session.id) },
                )
            }
        } else {
            Label(
                "[USUŃ SESJĘ]",
                color = p.grid,
                softWrap = false,
                modifier = Modifier.tap { onAskDelete(session.id) },
            )
        }
    }

    Spacer(Modifier.height(4.dp))
}

@Composable
private fun Stat(label: String, value: String, unit: String) {
    val p = Theme.palette

    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Label(label, color = p.phosphor, softWrap = false)

        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                color = p.readout,
                fontFamily = Type.Readout,
                fontSize = gridSp(22),
            )
            if (unit.isNotEmpty()) {
                Label(
                    " $unit",
                    color = p.dim,
                    softWrap = false,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
    }
}

private fun clock(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
}
