package pl.yggdrasil.narcissus2.domain

/**
 * NAJWAŻNIEJSZY WNIOSEK Z PIERWSZEJ BUDOWY.
 *
 * Poprzednio ekran wiedział o trybach: każdy warunek "a przy rowerze inaczej"
 * wchodził jako `if` wprost w Composable. Po trzech takich warunkach layout
 * przestał być czytelny, a dodanie czwartego trybu wymagało przejrzenia
 * całego pliku.
 *
 * Teraz metryka jest DANĄ: wie, jak się nazywa, w czym jest liczona i jak
 * sformatować surową wartość. Tryb deklaruje listę metryk. Ekran renderuje
 * listę i nie wie nic o trybach.
 *
 * Dodanie trybu = jedna pozycja w enumie. Zero zmian w UI.
 */

/** Surowy zestaw pomiarów. UI nigdy nie sięga tu bezpośrednio. */
data class Telemetry(
    val distanceM: Double = 0.0,
    val elapsedMs: Long = 0L,
    val movingMs: Long = 0L,
    val speedMps: Float = 0f,
    /** Prędkość wygładzona z ostatnich sekund — źródło tempa na ekranie. */
    val paceSpeedMps: Float = 0f,
    val avgMovingSpeedMps: Float = 0f,
    /** Numer bieżącego kilometra, liczony od 1. */
    val lapIndex: Int = 1,
    /** Średnia prędkość w bieżącym kilometrze (czas ruchu). */
    val lapSpeedMps: Float = 0f,
    val maxSpeedMps: Float = 0f,
    val totalSteps: Int = 0,
    val cadenceSpm: Int? = null,
    val acceptedFixes: Int = 0,
    val rejectedFixes: Int = 0,
    val lastAccuracyM: Float? = null,
    val moving: Boolean = false,
    val warmingUp: Boolean = false,
    /** Wysokość z GNSS — surowa, elipsoidalna. Patrz [AltitudeSource]. */
    val rawAltitudeM: Double? = null,
    /** Przewyższenie doliczone serwerowo. Null dopóki nie zsynchronizowano. */
    val ascentM: Double? = null,
)

/**
 * Definicja pojedynczego odczytu.
 *
 * [read] dostaje całą telemetrię i zwraca gotowy string, dzięki czemu metryka
 * może być złożona (np. fixy jako "142/7") bez specjalnego przypadku w UI.
 */
data class Metric(
    val id: String,
    val label: String,
    val unit: String,
    /** Etykieta zależna od stanu, np. numer kilometra. Null = stała [label]. */
    val labelOf: ((Telemetry) -> String)? = null,
    val read: (Telemetry) -> String,
)

object Metrics {

    val Distance = Metric("dist", "DYSTANS", "KM") {
        "%.2f".format(it.distanceM / 1000.0)
    }

    val Speed = Metric("spd", "PRĘDKOŚĆ", "KM/H") {
        "%.1f".format(it.speedMps * 3.6f)
    }

    val AvgSpeed = Metric("avgspd", "ŚREDNIA", "KM/H") {
        "%.1f".format(it.avgMovingSpeedMps * 3.6f)
    }

    val MaxSpeed = Metric("maxspd", "MAKS", "KM/H") {
        "%.1f".format(it.maxSpeedMps * 3.6f)
    }

    val Pace = Metric("pace", "TEMPO", "MIN/KM") {
        formatPace(it.paceSpeedMps)
    }

    val LapPace = Metric(
        id = "lappace",
        label = "TEMPO KM",
        unit = "MIN/KM",
        labelOf = { "TEMPO KM ${it.lapIndex}" },
    ) {
        formatPace(it.lapSpeedMps)
    }

    val AvgPace = Metric("avgpace", "TEMPO ŚREDNIE", "MIN/KM") {
        formatPace(it.avgMovingSpeedMps)
    }

    val Elapsed = Metric("time", "CZAS", "") {
        clock(it.elapsedMs)
    }

    val MovingTime = Metric("mtime", "W RUCHU", "") {
        clock(it.movingMs)
    }

    val Steps = Metric("steps", "KROKI", "") {
        it.totalSteps.toString()
    }

    val Cadence = Metric("cad", "KADENCJA", "KR/MIN") { t ->
        t.cadenceSpm?.toString() ?: "---"
    }

    val Fixes = Metric("fix", "POMIARY", "OK/REJ") {
        "${it.acceptedFixes}/${it.rejectedFixes}"
    }

    val Ascent = Metric("asc", "PRZEWYŻSZENIE", "M") { t ->
        t.ascentM?.let { "%.0f".format(it) } ?: "----"
    }
}

/**
 * Skąd bierze się wysokość.
 *
 * S20 FE nie ma barometru, więc jedyne źródło w telefonie to GNSS — a to jest
 * wysokość elipsoidalna z szumem rzędu kilkunastu metrów. Sumowanie takiego
 * sygnału daje setki metrów podjazdu na płaskim parkingu.
 *
 * Dlatego telefon zapisuje wyłącznie lat/lon, a przewyższenie liczy heimdall
 * przy synchronizacji, z modelu terenu (NMT/GUGiK). Wynik jest powtarzalny:
 * ten sam ślad zawsze da tę samą liczbę.
 */
enum class AltitudeSource {
    /** Nie pokazujemy wysokości na żywo. Domyślne. */
    None,

    /** Surowa z GNSS, wyraźnie oznaczona jako orientacyjna. */
    RawGnss,

    /** Doliczona serwerowo. Dostępna dopiero po synchronizacji. */
    Resolved,
}

/** Progi jakości sygnału, per tryb. Rower toleruje więcej niż spacer. */
data class Thresholds(
    val maxAccuracyM: Float,
    val minSpeedMps: Float,
    val maxSpeedMps: Float,
)

enum class ActivityMode(
    val label: String,
    val usesStepSensor: Boolean,
    val thresholds: Thresholds,
    /**
     * Czy pierwsza metryka idzie na duży wyświetlacz. Rower tak — telefon
     * wisi na kierownicy, pół metra od oczu. W biegu telefon jest w ręce,
     * więc wszystkie odczyty mają jeden, średni rozmiar.
     */
    val hero: Boolean,
    /** Kolejność ma znaczenie: przy [hero] pierwsza metryka trafia na duży wyświetlacz. */
    val metrics: List<Metric>,
) {
    Bike(
        label = "ROWER",
        usesStepSensor = false,
        thresholds = Thresholds(maxAccuracyM = 25f, minSpeedMps = 1.0f, maxSpeedMps = 30f),
        hero = true,
        metrics = listOf(
            Metrics.Speed,
            Metrics.Distance,
            Metrics.Elapsed,
            Metrics.AvgSpeed,
            Metrics.MaxSpeed,
            Metrics.Ascent,
        ),
    ),

    Run(
        label = "BIEG",
        usesStepSensor = true,
        thresholds = Thresholds(maxAccuracyM = 20f, minSpeedMps = 0.8f, maxSpeedMps = 8f),
        hero = false,
        metrics = listOf(
            Metrics.Distance,
            Metrics.LapPace,
            Metrics.AvgPace,
            Metrics.Cadence,
        ),
    ),

    Walk(
        label = "PIESZO",
        usesStepSensor = true,
        thresholds = Thresholds(maxAccuracyM = 15f, minSpeedMps = 0.4f, maxSpeedMps = 3f),
        hero = true,
        metrics = listOf(
            Metrics.Pace,
            Metrics.Distance,
            Metrics.Elapsed,
            Metrics.AvgPace,
            Metrics.Steps,
            Metrics.Fixes,
        ),
    ),
}

/** Tempo mm:ss na kilometr. Wolniej niż 30:00 to już nie ruch, tylko szum. */
private fun formatPace(speedMps: Float): String {
    if (speedMps <= 1000f / (30 * 60)) return "--:--"
    val total = (1000f / speedMps).toInt()
    return "%d:%02d".format(total / 60, total % 60)
}

private fun clock(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
}
