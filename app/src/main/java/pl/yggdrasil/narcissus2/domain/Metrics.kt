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
    val avgMovingSpeedMps: Float = 0f,
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
        formatPace(it.speedMps)
    }

    val AvgPace = Metric("avgpace", "ŚR. TEMPO", "MIN/KM") {
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

    val Cadence = Metric("cad", "KADENCJA", "SPM") {
        (it.cadenceSpm ?: 0).toString()
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
    /** Kolejność ma znaczenie: pierwsza metryka trafia na duży wyświetlacz. */
    val metrics: List<Metric>,
) {
    Bike(
        label = "ROWER",
        usesStepSensor = false,
        thresholds = Thresholds(maxAccuracyM = 25f, minSpeedMps = 1.0f, maxSpeedMps = 30f),
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
        metrics = listOf(
            Metrics.Pace,
            Metrics.Distance,
            Metrics.Elapsed,
            Metrics.AvgPace,
            Metrics.Cadence,
            Metrics.Steps,
        ),
    ),

    Walk(
        label = "PIESZO",
        usesStepSensor = true,
        thresholds = Thresholds(maxAccuracyM = 15f, minSpeedMps = 0.4f, maxSpeedMps = 3f),
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

/** Tempo mm:ss na kilometr. */
private fun formatPace(speedMps: Float): String {
    if (speedMps <= 0f) return "--:--"
    val total = (1000f / speedMps).toInt()
    return "%d:%02d".format(total / 60, total % 60)
}

private fun clock(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
}
