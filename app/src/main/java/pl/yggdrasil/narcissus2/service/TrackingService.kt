package pl.yggdrasil.narcissus2.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.PowerManager
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import pl.yggdrasil.narcissus2.MainActivity
import pl.yggdrasil.narcissus2.NarcissusApp
import pl.yggdrasil.narcissus2.R
import pl.yggdrasil.narcissus2.domain.Metrics
import pl.yggdrasil.narcissus2.i18n.Lang
import pl.yggdrasil.narcissus2.system.LocationSource

/**
 * Utrzymuje proces przy życiu na czas przejazdu.
 *
 * Liczy [TrackingController], ale pozycję w sesji pobiera serwis — żądanie
 * złożone przez foreground service typu location dostaje fixy także przy
 * zgaszonym ekranie. Poza sesją (czuwanie) o pozycję prosi kontroler.
 *
 * Powiadomienie pokazuje dystans i czas, żeby dało się je sprawdzić bez
 * wracania do aplikacji.
 */
class TrackingService : LifecycleService() {

    private var wakeLock: PowerManager.WakeLock? = null

    /** Jedno żądanie pozycji na serwis — onStartCommand bywa wołany ponownie. */
    private var locationJob: Job? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Foreground chroni proces przed ubiciem, ale NIE trzyma procesora
        // w czuwaniu. Bez tego po zgaszeniu ekranu pozycje przychodza raz
        // na kilkadziesiat sekund zamiast raz na sekunde.
        //
        // Blokade trzymamy tylko na czas sesji, zwalniana w onDestroy.
        // Dwanascie godzin to bezpiecznik na wypadek sesji, ktora sie
        // nigdy nie skonczy.
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "narcissus:tracking")
            .apply { acquire(12L * 60 * 60 * 1000) }

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(null),
            // Typ ZAWSZE location. Wcześniej tylko od Androida 14, a niżej 0 —
            // czyli serwis BEZ typu. Na Androidzie 11–13 (S20 FE, Moto G8)
            // aplikacja z uprawnieniem "podczas używania" dostaje pozycję
            // w tle tylko przez serwis typu location, więc przy zgaszonym
            // ekranie fixy przestawały przychodzić. Faza 1 wołała
            // startForeground bez typu, co bierze typ z manifestu — działało.
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )

        val controller = NarcissusApp.instance.controller

        // Pozycję w sesji pobiera serwis, jak w fazie 1 — żądanie ma
        // wtedy jednoznacznego właściciela o typie location. Spacer 29.09:
        // 19 z 30 minut bez fixów przy zgaszonym ekranie (serwis był wtedy
        // bez typu, patrz startForeground wyżej).
        if (locationJob?.isActive != true) locationJob = lifecycleScope.launch(Dispatchers.Default) {
            LocationSource(this@TrackingService).fixes(1_000L)
                .retryWhen { cause, _ ->
                    controller.locationError(cause.message)
                    delay(2_000L)
                    true
                }
                .collect { fix ->
                    controller.locationError(null)
                    controller.onFix(fix)
                }
        }

        lifecycleScope.launch {
            controller.state
                .map { s ->
                    // Klucz z tego, co faktycznie widać — obrazek rysujemy
                    // tylko, gdy zmieniła się któraś z wartości.
                    Triple(Metrics.Distance.read(s.telemetry) + Metrics.Elapsed.read(s.telemetry) + Lang.polish, s, s.active)
                }
                .distinctUntilChanged { a, b -> a.first == b.first && a.third == b.third }
                .collect { (_, s, active) ->
                    if (!active) {
                        // Sesja zakończona z poziomu aplikacji — serwis
                        // nie ma już czego pilnować.
                        stopSelf()
                        return@collect
                    }

                    NarcissusApp.instance.notifications.notify(
                        NOTIFICATION_ID,
                        notification(s),
                    )
                }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun notification(s: TrackingController.State?): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(this, NarcissusApp.CHANNEL_TRACKING)
            .setContentTitle(getString(R.string.app_name))
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)

        if (s != null) {
            val small = RemoteViews(packageName, R.layout.notification_readout).apply {
                setImageViewBitmap(R.id.readout, NotificationCard.compact(this@TrackingService, s.telemetry))
            }
            val big = RemoteViews(packageName, R.layout.notification_readout).apply {
                setImageViewBitmap(R.id.readout, NotificationCard.expanded(this@TrackingService, s.telemetry))
            }
            builder
                .setStyle(NotificationCompat.DecoratedCustomViewStyle())
                .setCustomContentView(small)
                .setCustomBigContentView(big)
        }

        return builder
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            // Dystans i czas widoczne też na ekranie blokady.
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "pl.yggdrasil.narcissus2.STOP"

        fun start(context: Context) {
            val intent = Intent(context, TrackingService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.startService(
                Intent(context, TrackingService::class.java).setAction(ACTION_STOP),
            )
        }
    }
}
