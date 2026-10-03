package pl.yggdrasil.narcissus2

import android.Manifest
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import pl.yggdrasil.narcissus2.system.BatteryExemption
import pl.yggdrasil.narcissus2.ui.ArchiveScreen
import pl.yggdrasil.narcissus2.ui.ArchiveViewModel
import pl.yggdrasil.narcissus2.ui.TrackingScreen
import pl.yggdrasil.narcissus2.ui.TrackingViewModel
import pl.yggdrasil.narcissus2.ui.components.crtGlitch
import pl.yggdrasil.narcissus2.ui.components.scanlines
import pl.yggdrasil.narcissus2.ui.theme.NarcissusTheme
import pl.yggdrasil.narcissus2.ui.theme.Theme

/** Dwa ekrany to za mało na bibliotekę nawigacyjną. Jedna zmienna wystarcza. */
private enum class Screen { Tracking, Archive }

class MainActivity : ComponentActivity() {

    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        // O wyjątek od baterii pytamy DOPIERO po uprawnieniach.
        //
        // Odwrotna kolejność była błędem: systemowe okno przejmowało ekran,
        // aplikacja szła w tło, a strumień pozycji startował w momencie,
        // gdy uprawnienia jeszcze nie było.
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true &&
            !BatteryExemption.isExempt(this)
        ) {
            BatteryExemption.request(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // Ekran nie gaśnie, dopóki aplikacja jest na wierzchu. Flaga działa
        // tylko na widocznym oknie, więc po przejściu w tło telefon zasypia
        // normalnie i nie trzeba jej samemu zdejmować.
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

            // W trakcie sesji licznik pokazuje się NA ekranie blokady: wciskasz
            // przycisk zasilania i od razu widzisz odczyty, bez odblokowania.
            // Tylko w sesji — poza nią aplikacja nie ma czego pokazywać
            // komuś, kto podniesie zablokowany telefon.
            LaunchedEffect(state.active) { setShowWhenLocked(state.active) }

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

            NarcissusTheme {
                // Linie jak na monitorze CRT — nad wszystkim, łącznie z paskami
                // systemu — i co jakiś czas zakłócenie obrazu pod nimi.
                Box(Modifier.fillMaxSize().scanlines().crtGlitch(Theme.palette.phosphor)) {
                    when (screen) {
                        Screen.Tracking -> TrackingScreen(
                            state = state,
                            onCommence = tracking::commence,
                            onTerminate = { tracking.terminate() },
                            onMode = tracking::setMode,
                            onTogglePosition = tracking::togglePosition,
                            onArchive = {
                                archive.refresh()
                                screen = Screen.Archive
                            },
                        )

                        Screen.Archive -> {
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
                                onServerUrl = archive::setServerUrl,
                            )
                        }
                    }
                }
            }
        }
    }
}
