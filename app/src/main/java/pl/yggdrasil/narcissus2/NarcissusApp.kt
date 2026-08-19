package pl.yggdrasil.narcissus2

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class NarcissusApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this

        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_TRACKING,
                getString(R.string.channel_tracking),
                // LOW: powiadomienie ma być widoczne, ale nie ma dzwonić
                // ani wibrować przy każdej aktualizacji dystansu.
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
