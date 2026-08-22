package pl.yggdrasil.narcissus2.ui

import android.app.Application
import android.location.Location
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pl.yggdrasil.narcissus2.data.SessionStore
import pl.yggdrasil.narcissus2.domain.ActivityMode
import pl.yggdrasil.narcissus2.domain.Session
import pl.yggdrasil.narcissus2.domain.Telemetry
import pl.yggdrasil.narcissus2.domain.TelemetryEngine
import pl.yggdrasil.narcissus2.domain.TrackPoint
import pl.yggdrasil.narcissus2.system.LocationSource
import pl.yggdrasil.narcissus2.system.StepSource
import pl.yggdrasil.narcissus2.system.SystemMonitor
import pl.yggdrasil.narcissus2.system.SystemStatus

data class TrackingUiState(
    val mode: ActivityMode = ActivityMode.Bike,
    val active: Boolean = false,
    val day: Boolean = false,
    val telemetry: Telemetry = Telemetry(),
    val system: SystemStatus = SystemStatus(),
    val positionVisible: Boolean = false,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val error: String? = null,
    /** Krótki komunikat po zakończeniu sesji — znika sam. */
    val notice: String? = null,
) {
    /** Odbiornik ma pozycję i dokładność mieszczącą się w progu trybu. */
    val ready: Boolean
        get() = system.gnss.hasFix &&
            (telemetry.lastAccuracyM ?: Float.MAX_VALUE) <= mode.thresholds.maxAccuracyM
}

class TrackingViewModel(app: Application) : AndroidViewModel(app) {

    private val monitor = SystemMonitor(app)
    private val locations = LocationSource(app)
    private val stepper = StepSource(app)
    private val store = SessionStore(app)

    private var engine: TelemetryEngine? = null
    private var sessionJobs = mutableListOf<Job>()

    private var sessionId: String? = null
    private var sessionStartedAt = 0L
    private var track: SessionStore.TrackWriter? = null

    /**
     * Odbiornik pracuje ZAWSZE, gdy aplikacja jest otwarta — w czuwaniu
     * tylko rzadziej. Zimny start GNSS to nawet minuta, więc czekanie
     * z tym do naciśnięcia START oznaczałoby utratę pierwszego kilometra.
     */
    private val interval = MutableStateFlow(STANDBY_INTERVAL_MS)

    private val _state = MutableStateFlow(TrackingUiState())
    val state: StateFlow<TrackingUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            monitor.status().collect { s -> _state.update { it.copy(system = s) } }
        }

        @OptIn(ExperimentalCoroutinesApi::class)
        viewModelScope.launch {
            interval
                .flatMapLatest { ms -> locations.fixes(ms) }
                .catch { t -> _state.update { it.copy(error = t.message) } }
                .collect(::onFix)
        }
    }

    private fun onFix(fix: Location) {
        val e = engine

        if (e == null) {
            // Czuwanie: karmimy tylko wskaźniki i panel pozycji.
            _state.update {
                it.copy(
                    telemetry = it.telemetry.copy(
                        lastAccuracyM = if (fix.hasAccuracy()) fix.accuracy else null,
                    ),
                    latitude = fix.latitude,
                    longitude = fix.longitude,
                )
            }
            return
        }

        val telemetry = e.onLocation(fix, System.currentTimeMillis())

        // Ślad piszemy przyrostowo — pad baterii zabiera ostatnie sekundy,
        // a nie całą trasę.
        track?.append(
            TrackPoint(
                timestamp = fix.time,
                latitude = fix.latitude,
                longitude = fix.longitude,
                accuracyM = if (fix.hasAccuracy()) fix.accuracy else 0f,
                speedMps = if (fix.hasSpeed()) fix.speed else 0f,
                altitudeM = if (fix.hasAltitude()) fix.altitude else null,
            ),
        )

        _state.update {
            it.copy(
                telemetry = telemetry,
                latitude = fix.latitude,
                longitude = fix.longitude,
            )
        }
    }

    fun setMode(mode: ActivityMode) {
        if (_state.value.active) return
        _state.update { it.copy(mode = mode) }
    }

    fun toggleDay() = _state.update { it.copy(day = !it.day) }

    fun togglePosition() = _state.update { it.copy(positionVisible = !it.positionVisible) }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    fun commence() {
        if (_state.value.active) return

        val mode = _state.value.mode
        val now = System.currentTimeMillis()

        engine = TelemetryEngine(mode).also { it.start(now) }
        sessionStartedAt = now
        sessionId = java.util.UUID.randomUUID().toString()
        track = sessionId?.let { store.openTrack(it) }

        interval.value = ACTIVE_INTERVAL_MS

        _state.update {
            it.copy(active = true, telemetry = Telemetry(), error = null, notice = null)
        }

        if (mode.usesStepSensor) {
            sessionJobs += viewModelScope.launch {
                stepper.steps().collect { total -> engine?.onStepCounter(total) }
            }
        }

        sessionJobs += viewModelScope.launch {
            while (true) {
                delay(1_000)
                val t = engine?.tick(System.currentTimeMillis()) ?: break
                _state.update { s ->
                    s.copy(telemetry = s.telemetry.copy(elapsedMs = t.elapsedMs))
                }
            }
        }
    }

    /**
     * Kończy sesję: zapisuje, potem ZERUJE ekran.
     *
     * Zostawianie liczb po STOP wyglądało jak zawieszenie — licznik pokazywał
     * dane, a linia stanu mówiła GOTOWOŚĆ. Podsumowanie należy do dziennika,
     * nie do ekranu roboczego. Tu zostaje krótki komunikat i tyle.
     */
    fun terminate(markAsTest: Boolean = false) {
        if (!_state.value.active) return

        sessionJobs.forEach { it.cancel() }
        sessionJobs.clear()

        val telemetry = _state.value.telemetry
        val mode = _state.value.mode
        val id = sessionId
        val points = track?.close() ?: 0
        track = null
        engine = null
        sessionId = null

        interval.value = STANDBY_INTERVAL_MS

        // Zerujemy natychmiast, nie czekając na zapis — ekran ma reagować
        // na dotknięcie, a nie na dysk.
        _state.update {
            it.copy(
                active = false,
                telemetry = Telemetry(lastAccuracyM = telemetry.lastAccuracyM),
                notice = null,
            )
        }

        if (id == null) return

        // Sesje bez ruchu nie zaśmiecają dziennika. Ślad i tak trzeba usunąć.
        if (telemetry.distanceM < MIN_SAVED_DISTANCE_M) {
            viewModelScope.launch {
                store.trackFile(id).delete()
                _state.update { it.copy(notice = "SESJA ODRZUCONA - BRAK RUCHU") }
            }
            return
        }

        val session = Session(
            id = id,
            mode = mode,
            startedAt = sessionStartedAt,
            endedAt = System.currentTimeMillis(),
            distanceM = telemetry.distanceM,
            elapsedMs = telemetry.elapsedMs,
            movingMs = telemetry.movingMs,
            avgMovingSpeedMps = telemetry.avgMovingSpeedMps,
            maxSpeedMps = telemetry.maxSpeedMps,
            totalSteps = telemetry.totalSteps,
            acceptedFixes = telemetry.acceptedFixes,
            rejectedFixes = telemetry.rejectedFixes,
            pointCount = points,
            test = markAsTest,
        )

        viewModelScope.launch {
            store.save(session)
            _state.update {
                it.copy(notice = "ZAPISANO %.2f KM".format(session.distanceM / 1000.0))
            }
            delay(4_000)
            _state.update { it.copy(notice = null) }
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Nie gubimy sesji przy zamknięciu — zapisuje się tak samo jak STOP.
        terminate()
    }

    companion object {
        private const val STANDBY_INTERVAL_MS = 4_000L
        private const val ACTIVE_INTERVAL_MS = 1_000L

        /** Poniżej tego dystansu sesja nie trafia do dziennika. */
        private const val MIN_SAVED_DISTANCE_M = 50.0
    }
}
