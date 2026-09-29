package pl.yggdrasil.narcissus2.system

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.telephony.CellSignalStrength
import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.onStart

/**
 * Trzy niezależne źródła scalone w jeden strumień.
 *
 * Każde źródło jest osobnym callbackFlow, żeby awaria (albo brak uprawnienia)
 * jednego nie wywracała pozostałych — brak GPS-a nie może zgasić wskaźnika
 * baterii.
 */
class SystemMonitor(private val context: Context) {

    /**
     * UWAGA NA combine: emituje pierwszą wartość dopiero wtedy, gdy KAŻDE
     * ze źródeł coś wypuści.
     *
     * GNSS odzywa się dopiero, gdy odbiornik zacznie raportować satelity —
     * po zimnym starcie potrafi to potrwać kilkanaście sekund. Bez wartości
     * startowych cały strumień milczy do tego momentu i wygaszone są też
     * bateria z zasięgiem, które dane mają od razu. Stąd onStart na każdym
     * źródle: pusty stan jest lepszy niż brak stanu.
     */
    fun status(): Flow<SystemStatus> =
        combine(
            gnss().onStart { emit(SystemStatus.Gnss()) },
            cellular().onStart { emit(SystemStatus.Cellular()) },
            battery().onStart { emit(SystemStatus.Battery()) },
        ) { g, c, b ->
            SystemStatus(g, c, b)
        }.conflate()

    // ----------------------------------------------------------------
    // GNSS
    // ----------------------------------------------------------------

    private fun gnss(): Flow<SystemStatus.Gnss> = callbackFlow {
        val lm = context.getSystemService(LocationManager::class.java)

        if (lm == null || !granted(Manifest.permission.ACCESS_FINE_LOCATION)) {
            trySend(SystemStatus.Gnss())
            awaitClose { }
            return@callbackFlow
        }

        val cb = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(s: GnssStatus) {
                var used = 0
                var top = 0f

                for (i in 0 until s.satelliteCount) {
                    if (s.usedInFix(i)) used++
                    val cn0 = s.getCn0DbHz(i)
                    if (cn0 > top) top = cn0
                }

                trySend(
                    SystemStatus.Gnss(
                        visible = s.satelliteCount,
                        usedInFix = used,
                        topCn0 = top,
                        // Cztery satelity to matematyczne minimum na fix 3D.
                        hasFix = used >= 4,
                    ),
                )
            }

            override fun onStopped() {
                trySend(SystemStatus.Gnss())
            }
        }

        try {
            lm.registerGnssStatusCallback(ContextCompat.getMainExecutor(context), cb)
        } catch (e: SecurityException) {
            trySend(SystemStatus.Gnss())
        }

        awaitClose {
            runCatching { lm.unregisterGnssStatusCallback(cb) }
        }
    }

    // ----------------------------------------------------------------
    // GSM / LTE / NR
    // ----------------------------------------------------------------

    /**
     * Poziom 0..4 bierzemy wprost z systemu — jest to ten sam poziom, który
     * pokazuje pasek statusu. Nie liczymy go sami z dBm, bo progi różnią się
     * między technologiami i bywają dostrajane przez producenta.
     */
    private fun cellular(): Flow<SystemStatus.Cellular> = callbackFlow {
        val tm = context.getSystemService(TelephonyManager::class.java)

        if (tm == null) {
            trySend(SystemStatus.Cellular(offline = true))
            awaitClose { }
            return@callbackFlow
        }

        fun push(s: SignalStrength) {
            val cells: List<CellSignalStrength> = s.cellSignalStrengths
            val best = cells.maxByOrNull { it.level }

            trySend(
                SystemStatus.Cellular(
                    level = best?.level ?: -1,
                    dbm = best?.dbm,
                    offline = cells.isEmpty(),
                ),
            )
        }

        // TelephonyCallback jest od Androida 12. Na 11 (Moto G8) samo
        // dotknięcie tej klasy wywalało aplikację przy starcie
        // (NoClassDefFoundError), więc tam idzie stary PhoneStateListener.
        val unregister: () -> Unit = try {
            val u = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                listenModern(tm, ::push)
            } else {
                listenLegacy(tm, ::push)
            }

            // Callback odzywa się przy ZMIANIE siły sygnału. Gdy zasięg stoi
            // w miejscu, pierwsza emisja potrafi nie przyjść przez długi czas
            // — więc bierzemy stan bieżący od razu przy rejestracji.
            tm.signalStrength?.let(::push)
            u
        } catch (e: SecurityException) {
            trySend(SystemStatus.Cellular())
            val noop: () -> Unit = {}
            noop
        }

        awaitClose { runCatching { unregister() } }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun listenModern(tm: TelephonyManager, push: (SignalStrength) -> Unit): () -> Unit {
        val cb = object : TelephonyCallback(), TelephonyCallback.SignalStrengthsListener {
            override fun onSignalStrengthsChanged(s: SignalStrength) = push(s)
        }
        tm.registerTelephonyCallback(ContextCompat.getMainExecutor(context), cb)
        return { tm.unregisterTelephonyCallback(cb) }
    }

    @Suppress("DEPRECATION")
    private fun listenLegacy(tm: TelephonyManager, push: (SignalStrength) -> Unit): () -> Unit {
        // Konstruktor z Executorem — ten bez argumentów wymaga Loopera,
        // a flow chodzi na Dispatchers.Default.
        val listener = object : PhoneStateListener(ContextCompat.getMainExecutor(context)) {
            override fun onSignalStrengthsChanged(s: SignalStrength) = push(s)
        }
        tm.listen(listener, PhoneStateListener.LISTEN_SIGNAL_STRENGTHS)
        return { tm.listen(listener, PhoneStateListener.LISTEN_NONE) }
    }

    // ----------------------------------------------------------------
    // BATERIA
    // ----------------------------------------------------------------

    /**
     * Bez uprawnień. ACTION_BATTERY_CHANGED to sticky broadcast, więc
     * pierwszy odczyt przychodzi natychmiast po rejestracji, bez czekania
     * na zmianę stanu.
     */
    private fun battery(): Flow<SystemStatus.Battery> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent == null) return

                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val tenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)

                trySend(
                    SystemStatus.Battery(
                        percent = if (level >= 0 && scale > 0) level * 100 / scale else -1,
                        charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                            status == BatteryManager.BATTERY_STATUS_FULL,
                        temperatureC = if (tenths == Int.MIN_VALUE) null else tenths / 10f,
                    ),
                )
            }
        }

        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        awaitClose {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    private fun granted(name: String): Boolean =
        ContextCompat.checkSelfPermission(context, name) == PackageManager.PERMISSION_GRANTED
}
