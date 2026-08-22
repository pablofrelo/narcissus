package pl.yggdrasil.narcissus2

import android.Manifest
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import pl.yggdrasil.narcissus2.ui.ArchiveScreen
import pl.yggdrasil.narcissus2.ui.ArchiveViewModel
import pl.yggdrasil.narcissus2.ui.TrackingScreen
import pl.yggdrasil.narcissus2.ui.TrackingViewModel
import pl.yggdrasil.narcissus2.ui.theme.NarcissusTheme

/** Dwa ekrany to za mało na bibliotekę nawigacyjną. Jedna zmienna wystarcza. */
private enum class Screen { Tracking, Archive }

class MainActivity : ComponentActivity() {

    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* Brak zgody obsługujemy w UI — wskaźnik GPS zostaje pusty. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Ekran nie gaśnie, dopóki aplikacja jest na wierzchu.
        //
        // To jest licznik przykręcony do kierownicy, a nie aplikacja, do
        // której się wraca — wygaszenie po minucie oznacza, że przez cały
        // przejazd patrzysz na czarną szybę. Flaga działa tylko na widocznym
        // oknie, więc po przejściu w tło telefon zasypia normalnie i nie
        // trzeba jej samemu zdejmować.
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        permissions.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACTIVITY_RECOGNITION,
                Manifest.permission.POST_NOTIFICATIONS,
            ),
        )

        setContent {
            val tracking: TrackingViewModel = viewModel()
            val archive: ArchiveViewModel = viewModel()

            val state by tracking.state.collectAsStateWithLifecycle()
            val archiveState by archive.state.collectAsStateWithLifecycle()

            var screen by remember { mutableStateOf(Screen.Tracking) }

            // Kontroler musi wiedzieć, czy patrzysz na ekran. Bez aktywnej
            // sesji i bez widocznego okna nie ma powodu trzymać włączonego
            // odbiornika GNSS — to najdroższy element całego licznika.
            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_START -> tracking.setForeground(true)
                        Lifecycle.Event.ON_STOP -> tracking.setForeground(false)
                        else -> Unit
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }

            NarcissusTheme(day = state.day) {
                when (screen) {
                    Screen.Tracking -> TrackingScreen(
                        state = state,
                        onCommence = tracking::commence,
                        onTerminate = { tracking.terminate() },
                        onMode = tracking::setMode,
                        onToggleDay = tracking::toggleDay,
                        onTogglePosition = tracking::togglePosition,
                        onArchive = {
                            // Odświeżamy przy wejściu, bo sesja mogła się
                            // właśnie zapisać.
                            archive.refresh()
                            screen = Screen.Archive
                        },
                    )

                    Screen.Archive -> {
                        // Systemowy gest wstecz: z sesji do listy,
                        // z listy do licznika.
                        BackHandler {
                            if (archiveState.opened != null) {
                                archive.close()
                            } else {
                                screen = Screen.Tracking
                            }
                        }

                        ArchiveScreen(
                            state = archiveState,
                            onOpen = archive::open,
                            onClose = archive::close,
                            onBack = { screen = Screen.Tracking },
                            onAskDelete = archive::askDelete,
                            onCancelDelete = archive::cancelDelete,
                            onConfirmDelete = archive::confirmDelete,
                            onSync = archive::runSync,
                            onDeleteTests = archive::deleteAllTest,
                        )
                    }
                }
            }
        }
    }
}
