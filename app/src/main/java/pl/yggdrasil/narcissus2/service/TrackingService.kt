package pl.yggdrasil.narcissus2.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import pl.yggdrasil.narcissus2.MainActivity
import pl.yggdrasil.narcissus2.NarcissusApp
import pl.yggdrasil.narcissus2.R

/**
 * Zapis śladu przy zgaszonym ekranie.
 *
 * Szkielet — silnik pomiarowy (filtrowanie fixów, liczenie dystansu,
 * czujnik kroków) wchodzi tutaj w następnym kroku. Serwis już teraz startuje
 * poprawnie jako foreground z typem "location", bo to jest ta część, którą
 * najłatwiej zepsuć i najtrudniej potem wyśledzić.
 */
class TrackingService : LifecycleService() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            } else {
                0
            },
        )

        return START_STICKY
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        return NotificationCompat.Builder(this, NarcissusApp.CHANNEL_TRACKING)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("ZAPIS TRASY AKTYWNY")
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 1
    }
}
