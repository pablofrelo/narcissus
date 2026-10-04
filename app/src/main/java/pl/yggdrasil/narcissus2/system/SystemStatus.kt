package pl.yggdrasil.narcissus2.system

/**
 * Stan podsystemów pokładowych.
 *
 * Wniosek z pierwszej budowy: wskaźniki NIE są częścią telemetrii przejazdu.
 * Bateria i zasięg zmieniają się niezależnie od tego, czy jedziesz, i muszą
 * być widoczne także w GOTOWOŚCI. Osobny strumień, osobny model.
 */
data class SystemStatus(
    val gnss: Gnss = Gnss(),
    val cellular: Cellular = Cellular(),
    val battery: Battery = Battery(),
) {
    data class Gnss(
        /** Ile satelitów widać w ogóle. */
        val visible: Int = 0,
        /** Ile faktycznie uczestniczy w wyliczeniu pozycji. */
        val usedInFix: Int = 0,
        /** Najlepszy stosunek sygnał/szum spośród widocznych, dBHz. */
        val topCn0: Float = 0f,
        val hasFix: Boolean = false,
    ) {
        /** 0..4 — tyle segmentów zapala wskaźnik. */
        val level: Int
            get() = when {
                !hasFix -> 0
                usedInFix >= 12 -> 4
                usedInFix >= 8 -> 3
                usedInFix >= 6 -> 2
                else -> 1
            }
    }

    data class Cellular(
        /** 0..4 wprost z systemu, -1 gdy nieznany. */
        val level: Int = -1,
        val dbm: Int? = null,
        /** Modem wyłączony albo brak karty. */
        val offline: Boolean = false,
        /** Nazwa sieci, w której telefon jest zalogowany. */
        val operator: String? = null,
        /** 2G / 3G / LTE / 5G — z rodzaju komórek, które widzi modem. */
        val tech: String? = null,
    )

    data class Battery(
        /** 0..100, -1 dopóki nie wiadomo. */
        val percent: Int = -1,
        val charging: Boolean = false,
        /** Stopnie Celsjusza. Przy godzinie GPS-a na słońcu potrafi zaboleć. */
        val temperatureC: Float? = null,
    ) {
        val level: Int
            get() = when {
                percent < 0 -> 0
                percent >= 80 -> 4
                percent >= 60 -> 3
                percent >= 40 -> 2
                percent >= 20 -> 1
                else -> 0
            }

        val critical: Boolean get() = percent in 0..14 && !charging
    }
}
