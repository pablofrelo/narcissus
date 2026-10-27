package pl.yggdrasil.narcissus2.system

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import pl.yggdrasil.narcissus2.i18n.tr

/**
 * Wyjęcie aplikacji spod optymalizacji baterii.
 *
 * Sam WakeLock nie wystarcza na Samsungu. One UI ma własną warstwę
 * oszczędzania ponad standardowym Doze: aplikacje trafiają do "uśpionych"
 * i dostają obcięte usługi w tle niezależnie od blokady procesora.
 */
object BatteryExemption {

    fun isExempt(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName)

    /**
     * Systemowe oszczędzanie energii. Wyjątek od optymalizacji nie chroni
     * przed nim: One UI w tym trybie dławi GPS w tle i fixy przestają
     * przychodzić przy zgaszonym ekranie.
     */
    fun isPowerSave(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isPowerSaveMode

    /** Co jest nie tak z energią, albo null, gdy nic. */
    fun problem(context: Context): String? = when {
        isPowerSave(context) -> tr("POWER SAVING - GPS AT RISK", "OSZCZĘDZANIE ENERGII - GPS ZAGROŻONY")
        !isExempt(context) -> tr("BATTERY OPTIMISED - GPS AT RISK", "OPTYMALIZACJA BATERII - GPS ZAGROŻONY")
        else -> null
    }

    @SuppressLint("BatteryLife")
    fun request(context: Context) {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { openSettings(context) }
    }

    fun openSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
