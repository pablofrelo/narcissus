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
import pl.yggdrasil.narcissus2.i18n.tr
import pl.yggdrasil.narcissus2.ui.components.Label
import pl.yggdrasil.narcissus2.ui.components.Panel
import pl.yggdrasil.narcissus2.ui.components.TrackView
import pl.yggdrasil.narcissus2.ui.components.tap
import pl.yggdrasil.narcissus2.ui.theme.Grid
import pl.yggdrasil.narcissus2.ui.theme.Theme
import pl.yggdrasil.narcissus2.ui.theme.Type
import pl.yggdrasil.narcissus2.ui.theme.gridSp
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.yggdrasil.narcissus2.share.StoryCard
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
                if (opened == null) tr("LOG", "DZIENNIK") else tr("SESSION", "SESJA"),
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
                    tr("[BACK]", "[POWRÓT]"),
                    color = p.phosphor,
                    softWrap = false,
                    modifier = Modifier.tap { if (opened == null) onBack() else onClose() },
                )
            }
        }

        state.syncMessage?.let { msg ->
            Label(
                msg,
                color = if (state.syncFailed) p.alarm else p.phosphor,
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
        Label(tr("LOADING...", "WCZYTYWANIE..."), color = p.dim)
        return
    }

    if (state.sessions.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Label(tr("NO SESSIONS RECORDED", "BRAK ZAPISANYCH SESJI"), color = p.dim, softWrap = false)
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
            tr("%d SESSIONS / %.1f KM", "%d SESJI / %.1f KM").format(state.sessions.size, totalKm),
            color = p.phosphor,
            softWrap = false,
        )

        // Pojawia się tylko wtedy, gdy jest co sprzątać — pusty przycisk
        // do kasowania niczego byłby tylko okazją do pomyłki.
        if (tests > 0) {
            Label(
                tr("[DELETE $tests TEST]", "[USUŃ $tests TEST.]"),
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
                if (session.test) "${session.mode.label.text} / TEST" else session.mode.label.text,
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
                fontSize = gridSp(Grid.VALUE_SMALL),
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
                Label(tr("TAP TO CANCEL", "DOTKNIJ BY ANULOWAĆ"), color = p.dim, softWrap = false)

                Label(
                    tr("[DELETE]", "[USUŃ]"),
                    color = p.alarm,
                    softWrap = false,
                    modifier = Modifier.tap(onConfirmDelete),
                )
            }
        } else {
            Label(
                "[X]",
                color = p.dim,
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
        DATE.format(Instant.ofEpochMilli(session.startedAt)) + "  //  " + session.mode.label.text,
        color = p.dim,
        softWrap = false,
    )

    Panel {
        if (state.trackLoading) {
            Label(tr("LOADING TRACK...", "WCZYTYWANIE ŚLADU..."), color = p.dim)
        } else {
            TrackView(state.track)
        }
    }

    Panel(Modifier.weight(1f)) {
        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.SpaceEvenly,
        ) {
            Stat(tr("DISTANCE", "DYSTANS"), "%.2f".format(session.distanceM / 1000.0), "KM")
            Stat(tr("TIME", "CZAS"), clock(session.elapsedMs), "")
            Stat(tr("MOVING", "W RUCHU"), clock(session.movingMs), "")
            Stat(tr("AVERAGE", "ŚREDNIA"), "%.1f".format(session.avgMovingSpeedMps * 3.6f), "KM/H")
            Stat(tr("MAX", "MAKS"), "%.1f".format(session.maxSpeedMps * 3.6f), "KM/H")

            if (session.mode.usesStepSensor) {
                Stat(tr("STEPS", "KROKI"), session.totalSteps.toString(), "")
            }

            Stat(tr("FIXES", "POMIARY"), "${session.acceptedFixes}/${session.rejectedFixes}", "OK/REJ")
            Stat(tr("POINTS", "PUNKTY"), session.pointCount.toString(), "")

            Stat(
                tr("ASCENT", "PRZEWYŻSZENIE"),
                session.ascentM?.let { "%.0f".format(it) } ?: "----",
                "M",
            )
        }
    }

    // --- relacja na FB: karta 9:16 z opcjonalnym zdjęciem w tle ---
    StoryActions(state, session)

    // Stan synchronizacji — bez tego nie wiadomo, czy sesja jest już
    // bezpieczna na heimdallu, czy istnieje tylko na telefonie.
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Label(
            if (session.synced) tr("SYNCED", "ZSYNCHRONIZOWANO") else tr("LOCAL ONLY", "TYLKO LOKALNIE"),
            color = if (session.synced) p.phosphor else p.amber,
            softWrap = false,
        )

        if (state.pendingDelete == session.id) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Label(
                    tr("[CANCEL]", "[ANULUJ]"),
                    color = p.dim,
                    softWrap = false,
                    modifier = Modifier.tap(onCancelDelete),
                )
                Label(
                    tr("[DELETE]", "[USUŃ]"),
                    color = p.alarm,
                    softWrap = false,
                    modifier = Modifier.tap { onConfirmDelete(session.id) },
                )
            }
        } else {
            Label(
                tr("[DELETE SESSION]", "[USUŃ SESJĘ]"),
                color = p.dim,
                softWrap = false,
                modifier = Modifier.tap { onAskDelete(session.id) },
            )
        }
    }

    Spacer(Modifier.height(4.dp))
}

/**
 * Dwie akcje: karta ze zdjęciem z galerii (zatopionym w tle) albo sama
 * karta na czerni. Rysowanie idzie w tle, potem systemowe okno
 * "Udostępnij" — stamtąd Facebook → Relacja.
 */
@Composable
private fun StoryActions(state: ArchiveUiState, session: Session) {
    val p = Theme.palette
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun make(photo: Uri?) {
        if (busy) return
        busy = true
        error = null
        val track = state.track
        scope.launch {
            try {
                val card = withContext(Dispatchers.Default) {
                    StoryCard.render(
                        context,
                        session,
                        track,
                        photo?.let { StoryCard.loadPhoto(context, it) },
                    )
                }
                StoryCard.share(context, card)
            } catch (e: Exception) {
                error = tr("CARD ERROR: ", "BŁĄD KARTY: ") + (e.message ?: e.javaClass.simpleName)
            } finally {
                busy = false
            }
        }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri -> if (uri != null) make(uri) }

    val ready = !state.trackLoading && !busy

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Label(
            if (busy) tr("RENDERING...", "RYSOWANIE...") else tr("[STORY + PHOTO]", "[RELACJA + ZDJĘCIE]"),
            color = if (ready) p.phosphor else p.dim,
            softWrap = false,
            modifier = Modifier.tap {
                if (ready) {
                    picker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                }
            },
        )
        Label(
            tr("[STORY]", "[RELACJA]"),
            color = if (ready) p.phosphor else p.dim,
            softWrap = false,
            modifier = Modifier.tap { if (ready) make(null) },
        )
    }

    error?.let { Label(it, color = p.alarm, softWrap = false) }
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
                fontSize = gridSp(Grid.VALUE_SMALL),
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
