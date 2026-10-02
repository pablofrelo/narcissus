package pl.yggdrasil.narcissus2.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pl.yggdrasil.narcissus2.data.SessionStore
import pl.yggdrasil.narcissus2.data.SyncClient
import pl.yggdrasil.narcissus2.data.SyncEngine
import pl.yggdrasil.narcissus2.data.SyncSettings
import pl.yggdrasil.narcissus2.domain.Session
import pl.yggdrasil.narcissus2.domain.TrackPoint
import pl.yggdrasil.narcissus2.i18n.tr

data class ArchiveUiState(
    val sessions: List<Session> = emptyList(),
    val loading: Boolean = true,
    /** Otwarta sesja. Null znaczy: widok listy. */
    val opened: Session? = null,
    val track: List<TrackPoint> = emptyList(),
    val trackLoading: Boolean = false,
    /** Sesja czekająca na potwierdzenie kasowania. */
    val pendingDelete: String? = null,
    val syncing: Boolean = false,
    /** Wynik ostatniej wymiany — sukces albo powód porażki. */
    val syncMessage: String? = null,
    val syncFailed: Boolean = false,
    val serverUrl: String = SyncSettings.DEFAULT_URL,
)

class ArchiveViewModel(app: Application) : AndroidViewModel(app) {

    private val store = SessionStore(app)
    private val settings = SyncSettings(app)
    private val sync = SyncEngine(store, SyncClient(settings), settings)

    private val _state = MutableStateFlow(ArchiveUiState())
    val state: StateFlow<ArchiveUiState> = _state.asStateFlow()

    init {
        _state.update { it.copy(serverUrl = settings.serverUrl) }
        refresh()
    }

    /**
     * Ręczna synchronizacja z serwerem.
     *
     * Świadomie ręczna, nie automatyczna: serwer jest osiągalny wyłącznie
     * w meshu WireGuard, więc automat próbowałby i zawodził za każdym razem,
     * gdy jesteś poza domem — czyli dokładnie wtedy, gdy jeździsz.
     */
    /** Nowy serwer = pełna wymiana od zera, bo znacznik "since" był od starego. */
    fun setServerUrl(url: String) {
        settings.serverUrl = url.trim()
        settings.lastSyncAt = 0L
        _state.update { it.copy(serverUrl = settings.serverUrl, syncMessage = null, syncFailed = false) }
    }

    fun runSync() {
        if (_state.value.syncing || _state.value.serverUrl.isBlank()) return

        viewModelScope.launch {
            _state.update { it.copy(syncing = true, syncMessage = null, syncFailed = false) }

            sync.sync()
                .mapCatching { r ->
                    // Przewyższenie serwer liczy w tle po odebraniu śladu.
                    // Krótką trasę ma gotową po kilku sekundach — druga
                    // wymiana od razu ją ściąga, bez drugiego kliknięcia.
                    if (r.tracksUp > 0) {
                        delay(ASCENT_WAIT_MS)
                        val again = sync.sync().getOrThrow()
                        r.copy(received = r.received + again.received)
                    } else {
                        r
                    }
                }
                .onSuccess { r ->
                    _state.update {
                        it.copy(
                            syncing = false,
                            syncMessage = tr("SENT", "WYSŁANO") + " ${r.sent} / " +
                                tr("RECEIVED", "ODEBRANO") + " ${r.received}" +
                                if (r.tracksUp + r.tracksDown > 0) {
                                    tr("  TRACKS", "  ŚLADY") + " ${r.tracksUp}↑ ${r.tracksDown}↓"
                                } else {
                                    ""
                                },
                        )
                    }
                    refresh()
                }
                .onFailure { t ->
                    _state.update {
                        it.copy(
                            syncing = false,
                            syncMessage = tr("ERROR: ", "BŁĄD: ") +
                                (t.message?.take(60) ?: tr("CONNECTION", "POŁĄCZENIE")),
                            syncFailed = true,
                        )
                    }
                }
        }
    }

    fun dismissSyncMessage() = _state.update { it.copy(syncMessage = null) }

    fun refresh() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            val list = store.all()
            _state.update { it.copy(sessions = list, loading = false) }
        }
    }

    fun open(session: Session) {
        _state.update { it.copy(opened = session, track = emptyList(), trackLoading = true) }

        viewModelScope.launch {
            val points = store.readTrack(session.id)
            _state.update { it.copy(track = points, trackLoading = false) }
        }
    }

    fun close() = _state.update {
        it.copy(opened = null, track = emptyList(), pendingDelete = null)
    }

    /** Kasowanie w dwóch krokach — przypadkowe dotknięcie nie kasuje przejazdu. */
    fun askDelete(id: String) = _state.update { it.copy(pendingDelete = id) }

    fun cancelDelete() = _state.update { it.copy(pendingDelete = null) }

    fun confirmDelete(id: String) {
        viewModelScope.launch {
            // Zostawia nagrobek, żeby sync nie przywrócił sesji przy
            // następnym połączeniu z heimdallem.
            store.delete(id)
            _state.update { it.copy(opened = null, pendingDelete = null) }
            refresh()
        }
    }

    fun deleteAllTest() {
        viewModelScope.launch {
            store.deleteAllTest()
            refresh()
        }
    }

    companion object {
        /** Tyle czekamy na przewyższenie przed drugą wymianą. */
        private const val ASCENT_WAIT_MS = 8_000L
    }
}
