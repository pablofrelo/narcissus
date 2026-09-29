package pl.yggdrasil.narcissus2.data

import android.content.Context
import java.util.UUID

/**
 * Ustawienia synchronizacji. SharedPreferences, bo to trzy wartości —
 * DataStore byłby tu ceremonią bez treści.
 */
class SyncSettings(context: Context) {

    private val prefs = context.getSharedPreferences("sync", Context.MODE_PRIVATE)

    /**
     * Adres serwera w meshu WireGuard. Poza meshem nieosiągalny i to jest
     * cecha, nie wada — nie ma tu żadnego uwierzytelniania, bo sam mesh
     * pełni tę rolę.
     */
    var serverUrl: String
        get() = prefs.getString("url", DEFAULT_URL) ?: DEFAULT_URL
        set(v) = prefs.edit().putString("url", v.trimEnd('/')).apply()

    /** Identyfikator tego urządzenia. Do rozróżnienia źródeł w logach serwera. */
    val deviceId: String
        get() = prefs.getString("device", null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString("device", it).apply()
        }

    // Nowy klucz = jednorazowa pełna wymiana po aktualizacji. Do 29.09 (lastSync3)
    // telefon odrzucał sesje z serwera (parsowanie nulli, potem nadpisywanie
    // odebranych starą kopią), więc przewyższenia trzeba ściągnąć od zera.
    var lastSyncAt: Long
        get() = prefs.getLong("lastSync3", 0L)
        set(v) = prefs.edit().putLong("lastSync3", v).apply()

    companion object {
        const val DEFAULT_URL = "http://10.8.0.1:8765"
    }
}
