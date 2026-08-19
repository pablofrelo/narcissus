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
import pl.yggdrasil.narcissus2.domain.ActivityMode
import pl.yggdrasil.narcissus2.domain.Telemetry
import pl.yggdrasil.narcissus2.domain.TelemetryEngine
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

    private var engine: TelemetryEngine? = null
    private var sessionJobs = mutableListOf<Job>()

    /**
     * Odbiornik pracuje ZAWSZE, gdy aplikacja jest otwarta — w czuwaniu
     * tylko rzadziej.
     *
     * Powód: zimny start GNSS to kilkadziesiąt sekund do minuty. Gdyby
     * odbiornik ruszał dopiero po naciśnięciu START, pierwsze pół kilometra
     * przejazdu byłoby nieznane. Licznik ma być gotowy, zanim wsiądziesz.
     *
     * Kosztem jest bateria, dlatego w czuwaniu pytamy co cztery sekundy —
     * to wystarcza, żeby utrzymać fix, i jest wyraźnie tańsze niż sekunda.
     */
    private val interval = MutableStateFlow(STANDBY_INTERVAL_MS)

    private val _state = MutableStateFlow(TrackingUiState())
    val state: StateFlow<TrackingUiState> = _state.asStateFlow()

    init {
        // Wskaźniki podsystemów — niezależne od przejazdu.
        viewModelScope.launch {
            monitor.status().collect { s -> _state.update { it.copy(system = s) } }
        }

        // Jeden strumień pozycji na całe życie ekranu. Zmiana interwału
        // restartuje go bez gubienia ciągłości logiki wyżej.
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
            // Czuwanie: karmimy tylko wskaźniki i panel pozycji. Dokładność
            // trafia do telemetrii, żeby linia stanu mogła pokazać GOTOWOŚĆ
            // z konkretną liczbą metrów zamiast samego napisu.
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

    fun commence() {
        if (_state.value.active) return

        val mode = _state.value.mode
        engine = TelemetryEngine(mode).also { it.start(System.currentTimeMillis()) }

        // Przejazd potrzebuje gęstszych pomiarów niż czuwanie.
        interval.value = ACTIVE_INTERVAL_MS

        _state.update { it.copy(active = true, telemetry = Telemetry(), error = null) }

        if (mode.usesStepSensor) {
            sessionJobs += viewModelScope.launch {
                stepper.steps().collect { total -> engine?.onStepCounter(total) }
            }
        }

        // Zegar tyka niezależnie od fixów — inaczej CZAS stoi w miejscu,
        // dopóki nie przyjdzie pierwszy pomiar, co wygląda na zawieszenie.
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

    fun terminate() {
        sessionJobs.forEach { it.cancel() }
        sessionJobs.clear()
        engine = null

        // Wracamy do czuwania, a nie do wyłączenia — po zatrzymaniu licznik
        // nadal ma wiedzieć, gdzie jest, na wypadek szybkiego restartu.
        interval.value = STANDBY_INTERVAL_MS

        _state.update { it.copy(active = false) }
    }

    override fun onCleared() {
        super.onCleared()
        terminate()
    }

    companion object {
        private const val STANDBY_INTERVAL_MS = 4_000L
        private const val ACTIVE_INTERVAL_MS = 1_000L
    }
}
