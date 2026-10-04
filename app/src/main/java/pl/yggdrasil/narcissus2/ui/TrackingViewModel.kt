package pl.yggdrasil.narcissus2.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import pl.yggdrasil.narcissus2.NarcissusApp
import pl.yggdrasil.narcissus2.domain.ActivityMode
import pl.yggdrasil.narcissus2.domain.Telemetry
import pl.yggdrasil.narcissus2.service.TrackingService
import pl.yggdrasil.narcissus2.system.SystemStatus
import pl.yggdrasil.narcissus2.ui.components.Detail

data class TrackingUiState(
    val mode: ActivityMode = ActivityMode.Bike,
    val active: Boolean = false,
    val telemetry: Telemetry = Telemetry(),
    val system: SystemStatus = SystemStatus(),
    val detail: Detail? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val error: String? = null,
    val notice: String? = null,
) {
    val ready: Boolean
        get() = system.gnss.hasFix &&
            (telemetry.lastAccuracyM ?: Float.MAX_VALUE) <= mode.thresholds.maxAccuracyM
}

/** Rzeczy, które dotyczą wyłącznie wyglądu i nie mają prawa przeżyć ekranu. */
private data class LocalUi(
    val detail: Detail? = null,
)

/**
 * ViewModel jest teraz cienki: pomiar mieszka w kontrolerze przypiętym do
 * procesu, tutaj zostaje tylko stan czysto ekranowy (rozwinięty panel
 * szczegółów) i przekazywanie poleceń dalej.
 */
class TrackingViewModel(app: Application) : AndroidViewModel(app) {

    private val controller = NarcissusApp.instance.controller
    private val local = MutableStateFlow(LocalUi())

    val state: StateFlow<TrackingUiState> =
        combine(controller.state, local) { c, ui ->
            TrackingUiState(
                mode = c.mode,
                active = c.active,
                telemetry = c.telemetry,
                system = c.system,
                detail = ui.detail,
                latitude = c.latitude,
                longitude = c.longitude,
                error = c.error,
                notice = c.notice,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TrackingUiState())

    fun setMode(mode: ActivityMode) = controller.setMode(mode)

    /** Ten sam wskaźnik zwija, inny przełącza panel. */
    fun toggleDetail(d: Detail) = local.update { it.copy(detail = if (it.detail == d) null else d) }

    fun closeDetail() = local.update { it.copy(detail = null) }

    fun commence() {
        controller.commence()
        // Serwis startuje PO rozpoczęciu sesji, żeby pierwsze powiadomienie
        // miało już sensowną treść.
        TrackingService.start(getApplication())
    }

    fun armStop() = controller.armStop()

    fun disarmStop() = controller.disarmStop()

    fun terminate() {
        controller.terminate()
        TrackingService.stop(getApplication())
    }

    /** Ekran widoczny lub nie — decyduje, czy trzymać GNSS w czuwaniu. */
    fun setForeground(visible: Boolean) = controller.setForeground(visible)
}
