package pl.yggdrasil.narcissus2.system

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import pl.yggdrasil.narcissus2.i18n.tr

/**
 * Źródło pozycji.
 *
 * Surowy GPS_PROVIDER, nie Fused — poza miastem surowy GNSS wypada lepiej,
 * bo Fused dokłada zgadywanie z sieci i czujników, a tam gdzie nie ma
 * masztów to zgadywanie tylko szkodzi.
 *
 * WAŻNE: to wywołanie faktycznie WŁĄCZA odbiornik. Sam nasłuch statusu
 * satelitów (SystemMonitor) nie budzi sprzętu.
 */
class LocationSource(private val context: Context) {

    fun fixes(intervalMs: Long = 1_000L): Flow<Location> = callbackFlow {
        val lm = context.getSystemService(LocationManager::class.java)

        // Rzucamy wyjątek zamiast cicho zamykać strumień, bo wyżej siedzi
        // retryWhen. Dzięki temu przyznanie uprawnienia albo włączenie GPS-u
        // w trakcie działania aplikacji podnosi pomiar samo, bez restartu.
        if (lm == null) {
            throw IllegalStateException(tr("NO LOCATION SERVICE", "BRAK USŁUGI LOKALIZACJI"))
        }

        if (!granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            throw SecurityException(tr("NO LOCATION PERMISSION", "BRAK UPRAWNIENIA DO POZYCJI"))
        }

        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            throw IllegalStateException(tr("GPS DISABLED IN SYSTEM", "GPS WYŁĄCZONY W SYSTEMIE"))
        }

        val listener = LocationListener { location -> trySend(location) }

        lm.requestLocationUpdates(
            LocationManager.GPS_PROVIDER,
            intervalMs,
            // Zero metrów: filtrowanie robimy sami w TelemetryEngine, gdzie
            // mamy kontekst trybu i możemy odróżnić szum od ruchu.
            0f,
            ContextCompat.getMainExecutor(context),
            listener,
        )

        awaitClose {
            runCatching { lm.removeUpdates(listener) }
        }
    }

    private fun granted(name: String): Boolean =
        ContextCompat.checkSelfPermission(context, name) == PackageManager.PERMISSION_GRANTED
}

/**
 * Licznik kroków.
 *
 * TYPE_STEP_COUNTER zwraca sumę od ostatniego restartu telefonu, nie od
 * startu aplikacji — przeliczenie na kroki bieżącej sesji robi silnik.
 * Czujnik jest sprzętowy i praktycznie nie kosztuje baterii.
 */
class StepSource(private val context: Context) {

    fun steps(): Flow<Int> = callbackFlow {
        val sm = context.getSystemService(SensorManager::class.java)
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

        if (sensor == null) {
            awaitClose { }
            return@callbackFlow
        }

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                trySend(event.values.firstOrNull()?.toInt() ?: return)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }

        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)

        awaitClose {
            runCatching { sm.unregisterListener(listener) }
        }
    }
}
