package pl.yggdrasil.narcissus2

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import pl.yggdrasil.narcissus2.service.TrackingController

class NarcissusApp : Application() {

    /**
     * Kontroler żyje tak długo jak proces, a nie jak ekran. To dlatego
     * przejazd przeżywa zgaszenie wyświetlacza i przełączenie aplikacji.
     */
    lateinit var controller: TrackingController
        private set

    val notifications: NotificationManager
        get() = getSystemService(NotificationManager::class.java)

    override fun onCreate() {
        super.onCreate()
        instance = this
        controller = TrackingController(this)

        notifications.createNotificationChannel(
            NotificationChannel(
                CHANNEL_TRACKING,
                getString(R.string.channel_tracking),
                // LOW: powiadomienie ma być widoczne, ale nie dzwonić
                // przy każdej aktualizacji dystansu.
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    companion object {
        const val CHANNEL_TRACKING = "tracking"

        lateinit var instance: NarcissusApp
            private set
    }
}
