package pl.yggdrasil.narcissus2

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.core.view.WindowCompat
import pl.yggdrasil.narcissus2.ui.TrackingScreen
import pl.yggdrasil.narcissus2.ui.TrackingViewModel
import pl.yggdrasil.narcissus2.ui.theme.NarcissusTheme

class MainActivity : ComponentActivity() {

    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* Brak zgody obsługujemy w UI — wskaźnik GPS po prostu zostaje pusty. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Ekran nie gaśnie w trakcie przejazdu — pełni rolę licznika
        // przykręconego do kierownicy.
        WindowCompat.setDecorFitsSystemWindows(window, false)

        permissions.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.ACTIVITY_RECOGNITION,
                Manifest.permission.POST_NOTIFICATIONS,
            ),
        )

        setContent {
            val vm: TrackingViewModel = viewModel()
            val state by vm.state.collectAsStateWithLifecycle()

            NarcissusTheme(day = state.day) {
                TrackingScreen(
                    state = state,
                    onCommence = vm::commence,
                    onTerminate = vm::terminate,
                    onMode = vm::setMode,
                    onToggleDay = vm::toggleDay,
                    onTogglePosition = vm::togglePosition,
                    onArchive = { /* TODO: ekran dziennika */ },
                )
            }
        }
    }
}
