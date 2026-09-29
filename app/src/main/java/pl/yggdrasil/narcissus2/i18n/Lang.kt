package pl.yggdrasil.narcissus2.i18n

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Język interfejsu: angielski domyślnie, polski z przełącznika w nagłówku.
 *
 * Własny przełącznik, nie locale systemu: Android 11 (Moto G8) nie ma
 * języka per aplikacja, a telefon po polsku nie musi znaczyć licznika
 * po polsku. Dwa języki, więc teksty stoją parami w kodzie zamiast
 * w strings.xml — [tr] czyta się od razu, bez skakania po kluczach.
 *
 * [polish] jest stanem Compose: przełączenie przerysowuje ekran od razu.
 */
object Lang {
    var polish by mutableStateOf(false)
        private set

    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences("ui", Context.MODE_PRIVATE).also {
            polish = it.getBoolean("polish", false)
        }
    }

    fun toggle() {
        polish = !polish
        prefs?.edit()?.putBoolean("polish", polish)?.apply()
    }
}

fun tr(en: String, pl: String): String = if (Lang.polish) pl else en

/** Tekst stały w dwóch językach — do definicji, które żyją dłużej niż ekran. */
class Txt(private val en: String, private val pl: String) {
    /** Tak samo w obu językach, np. jednostki "KM/H". */
    constructor(both: String) : this(both, both)

    val text: String get() = tr(en, pl)
}
