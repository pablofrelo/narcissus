package pl.yggdrasil.narcissus2.service

import android.content.Context
import android.location.Location
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pl.yggdrasil.narcissus2.data.SessionStore
import pl.yggdrasil.narcissus2.domain.ActivityMode
import pl.yggdrasil.narcissus2.domain.Session
import pl.yggdrasil.narcissus2.domain.Telemetry
import pl.yggdrasil.narcissus2.domain.TelemetryEngine
import pl.yggdrasil.narcissus2.domain.TrackPoint
import pl.yggdrasil.narcissus2.i18n.tr
import pl.yggdrasil.narcissus2.system.LocationSource
import pl.yggdrasil.narcissus2.system.StepSource
import pl.yggdrasil.narcissus2.system.SystemMonitor
import pl.yggdrasil.narcissus2.system.SystemStatus
import java.util.UUID

/**
 * Serce licznika, wyprowadzone poza ViewModel.
 *
 * DLACZEGO NIE VIEWMODEL: ViewModel żyje tak długo, jak ekran. Zgaszenie
 * wyświetlacza albo przełączenie na inną aplikację kończyło pomiar
 * w połowie trasy. Teraz stan żyje w obiekcie przypiętym do procesu,
 * a foreground service trzyma ten proces przy życiu.
 */
class TrackingController(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val monitor = SystemMonitor(context)
    private val locations = LocationSource(context)
    private val stepper = StepSource(context)
    private val store = SessionStore(context)

    data class State(
        val mode: ActivityMode = ActivityMode.Bike,
        val active: Boolean = false,
        val telemetry: Telemetry = Telemetry(),
        val system: SystemStatus = SystemStatus(),
        val latitude: Double? = null,
        val longitude: Double? = null,
        val notice: String? = null,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var engine: TelemetryEngine? = null
    private var sessionJobs = mutableListOf<Job>()
    private var sessionId: String? = null
    private var sessionStartedAt = 0L
    private var track: SessionStore.TrackWriter? = null

    /** Czy ekran aplikacji jest na wierzchu. */
    private var foreground = false

    /**
     * Częstotliwość pomiarów. Null wyłącza odbiornik.
     *
     * W przejeździe co sekundę. W czuwaniu co cztery, ale TYLKO gdy ekran
     * jest widoczny — bez aktywnej sesji nie ma powodu trzymać GNSS-u
     * włączonego, a to najdroższy element w całym liczniku.
     */
    private val interval = MutableStateFlow<Long?>(null)

    init {
        scope.launch {
            monitor.status().collect { s -> _state.update { it.copy(system = s) } }
        }

        @OptIn(ExperimentalCoroutinesApi::class)
        scope.launch {
            interval
                .flatMapLatest { ms -> if (ms == null) emptyFlow() else locations.fixes(ms) }
                // UWAGA: samo catch tu NIE wystarcza, bo catch KOŃCZY
                // strumień. Gdy pierwsza próba wypadnie przed przyznaniem
                // uprawnienia do pozycji, odbiornik zostaje martwy aż do
                // restartu aplikacji. retryWhen próbuje dalej, więc
                // przyznanie uprawnienia albo włączenie GPS-u podnosi
                // pomiar samo.
                .retryWhen { cause, attempt ->
                    locationError(cause.message)
                    delay(if (attempt < 5) 2_000L else 10_000L)
                    true
                }
                .collect { fix ->
                    locationError(null)
                    onFix(fix)
                }
        }
    }

    // ----------------------------------------------------------------

    fun setForeground(visible: Boolean) {
        foreground = visible
        retune()
    }

    private fun retune() {
        interval.value = when {
            // W sesji pozycję pobiera sam serwis — patrz TrackingService.
            _state.value.active -> null
            foreground -> STANDBY_INTERVAL_MS
            else -> null
        }
    }

    fun setMode(mode: ActivityMode) {
        if (_state.value.active) return
        _state.update { it.copy(mode = mode) }
    }

    // ----------------------------------------------------------------

    internal fun locationError(message: String?) {
        if (_state.value.error != message) _state.update { it.copy(error = message) }
    }

    internal fun onFix(fix: Location) {
        val e = engine

        if (e == null) {
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
                // Przy wciśniętym STOP ekran stoi na stanie z chwili dotknięcia,
                // a silnik liczy dalej pod spodem na wypadek puszczenia.
                telemetry = stopMark?.first ?: telemetry,
                latitude = fix.latitude,
                longitude = fix.longitude,
            )
        }
    }

    fun commence() {
        if (_state.value.active) return

        val now = System.currentTimeMillis()
        val st = _state.value
        val warm = st.system.gnss.hasFix &&
            (st.telemetry.lastAccuracyM ?: Float.MAX_VALUE) <= st.mode.thresholds.maxAccuracyM
        engine = TelemetryEngine(st.mode).also { it.start(now, warm) }
        sessionStartedAt = now
        sessionId = UUID.randomUUID().toString()
        track = sessionId?.let { store.openTrack(it) }

        _state.update { it.copy(active = true, telemetry = Telemetry(), notice = null) }
        retune()

        if (_state.value.mode.usesStepSensor) {
            sessionJobs += scope.launch {
                stepper.steps().collect { total ->
                    engine?.onStepCounter(total, System.currentTimeMillis())
                }
            }
        }

        sessionJobs += scope.launch {
            while (true) {
                delay(1_000)
                val t = engine?.tick(System.currentTimeMillis()) ?: break
                if (stopMark != null) continue
                _state.update { s ->
                    // Kroki i kadencja lecą także bez nowego fixa, np. w tunelu.
                    s.copy(
                        telemetry = s.telemetry.copy(
                            elapsedMs = t.elapsedMs,
                            totalSteps = t.totalSteps,
                            cadenceSpm = t.cadenceSpm,
                        ),
                    )
                }
            }
        }
    }

    /**
     * Stan w chwili DOTKNIĘCIA przycisku STOP. Przytrzymanie trwa 2 s,
     * a te 2 s nie mogą wejść do wyniku — sesja kończy się tym, co było
     * w momencie położenia palca. Puszczenie wcześniej kasuje zapis.
     */
    private var stopMark: Pair<Telemetry, Long>? = null

    fun armStop() {
        if (!_state.value.active) return
        val now = System.currentTimeMillis()
        val frozen = _state.value.telemetry.copy(elapsedMs = now - sessionStartedAt)
        stopMark = frozen to now
        _state.update { it.copy(telemetry = frozen) }
    }

    fun disarmStop() {
        stopMark = null
        // Odmrożenie: zegar od razu wraca do bieżącego czasu, dystans
        // i tempo z najbliższym fixem.
        val t = engine?.tick(System.currentTimeMillis()) ?: return
        _state.update {
            it.copy(
                telemetry = it.telemetry.copy(
                    elapsedMs = t.elapsedMs,
                    totalSteps = t.totalSteps,
                    cadenceSpm = t.cadenceSpm,
                ),
            )
        }
    }

    fun terminate(markAsTest: Boolean = false) {
        if (!_state.value.active) return
        val mark = stopMark
        stopMark = null

        sessionJobs.forEach { it.cancel() }
        sessionJobs.clear()

        val telemetry = mark?.first ?: _state.value.telemetry
        val endedAt = mark?.second ?: System.currentTimeMillis()
        val mode = _state.value.mode
        val id = sessionId
        val points = track?.close() ?: 0

        track = null
        engine = null
        sessionId = null

        _state.update {
            it.copy(
                active = false,
                telemetry = Telemetry(lastAccuracyM = telemetry.lastAccuracyM),
            )
        }
        retune()

        if (id == null) return

        if (telemetry.distanceM < MIN_SAVED_DISTANCE_M) {
            scope.launch {
                store.trackFile(id).delete()
                flash(tr("SESSION DISCARDED - NO MOVEMENT", "SESJA ODRZUCONA - BRAK RUCHU"))
            }
            return
        }

        val session = Session(
            id = id,
            mode = mode,
            startedAt = sessionStartedAt,
            endedAt = endedAt,
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

        scope.launch {
            store.save(session)
            flash(tr("SAVED %.2f KM", "ZAPISANO %.2f KM").format(session.distanceM / 1000.0))
        }
    }

    private suspend fun flash(text: String) {
        _state.update { it.copy(notice = text) }
        delay(4_000)
        _state.update { if (it.notice == text) it.copy(notice = null) else it }
    }

    companion object {
        private const val STANDBY_INTERVAL_MS = 4_000L
        private const val MIN_SAVED_DISTANCE_M = 50.0
    }
}
