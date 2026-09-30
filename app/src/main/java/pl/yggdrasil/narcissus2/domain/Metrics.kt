package pl.yggdrasil.narcissus2.domain

import java.util.Locale
import pl.yggdrasil.narcissus2.i18n.Txt
import pl.yggdrasil.narcissus2.i18n.tr

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
    /** Jak [avgMovingSpeedMps], ale odświeżana co 15 s — źródło tempa średniego. */
    val avgPaceSpeedMps: Float = 0f,
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
    val label: Txt,
    val unit: Txt,
    /** Etykieta zależna od stanu, np. numer kilometra. Null = stała [label]. */
    val labelOf: ((Telemetry) -> String)? = null,
    val read: (Telemetry) -> String,
)

object Metrics {

    val Distance = Metric("dist", Txt("DISTANCE", "DYSTANS"), Txt("KM")) {
        fmt("%6.2f", it.distanceM / 1000.0)
    }

    val Speed = Metric("spd", Txt("SPEED", "PRĘDKOŚĆ"), Txt("KM/H")) {
        fmt("%5.1f", it.speedMps * 3.6f)
    }

    val AvgSpeed = Metric("avgspd", Txt("AVERAGE", "ŚREDNIA"), Txt("KM/H")) {
        fmt("%5.1f", it.avgMovingSpeedMps * 3.6f)
    }

    val MaxSpeed = Metric("maxspd", Txt("MAX", "MAKS"), Txt("KM/H")) {
        fmt("%5.1f", it.maxSpeedMps * 3.6f)
    }

    val Pace = Metric("pace", Txt("PACE", "TEMPO"), Txt("MIN/KM")) {
        formatPace(it.paceSpeedMps)
    }

    val LapPace = Metric(
        id = "lappace",
        label = Txt("PACE KM", "TEMPO KM"),
        unit = Txt("MIN/KM"),
        labelOf = { tr("PACE KM ${it.lapIndex}", "TEMPO KM ${it.lapIndex}") },
    ) {
        formatPace(it.lapSpeedMps)
    }

    /** Odświeżane co 15 s — przy każdym fixie drgałoby o sekundy. */
    val AvgPace = Metric("avgpace", Txt("AVG PACE", "TEMPO ŚR."), Txt("MIN/KM")) {
        formatPace(it.avgPaceSpeedMps)
    }

    val Elapsed = Metric("time", Txt("TIME", "CZAS"), Txt("")) {
        clock(it.elapsedMs)
    }

    val MovingTime = Metric("mtime", Txt("MOVING", "W RUCHU"), Txt("")) {
        clock(it.movingMs)
    }

    val Steps = Metric("steps", Txt("STEPS", "KROKI"), Txt("")) {
        fmt("%5d", it.totalSteps)
    }

    val Cadence = Metric("cad", Txt("CADENCE", "KADENCJA"), Txt("SPM", "KR/MIN")) { t ->
        t.cadenceSpm?.let { fmt("%3d", it) } ?: "---"
    }

    val Fixes = Metric("fix", Txt("FIXES", "POMIARY"), Txt("OK/REJ")) {
        fmt("%4d/%d", it.acceptedFixes, it.rejectedFixes)
    }

    val Ascent = Metric("asc", Txt("ASCENT", "PRZEWYŻSZENIE"), Txt("M")) { t ->
        t.ascentM?.let { fmt("%4.0f", it) } ?: "----"
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
    val label: Txt,
    val usesStepSensor: Boolean,
    val thresholds: Thresholds,
    /** Czy pierwsza metryka idzie na duży wyświetlacz. */
    val hero: Boolean,
    /** Kolejność ma znaczenie: przy [hero] pierwsza metryka trafia na duży wyświetlacz. */
    val metrics: List<Metric>,
) {
    Bike(
        label = Txt("BIKE", "ROWER"),
        usesStepSensor = false,
        thresholds = Thresholds(maxAccuracyM = 25f, minSpeedMps = 1.0f, maxSpeedMps = 30f),
        hero = true,
        // Przewyższenia nie ma na żywym ekranie: liczy je serwer po
        // synchronizacji, więc w trakcie jazdy zawsze pokazywałoby "----".
        metrics = listOf(
            Metrics.Speed,
            Metrics.Distance,
            Metrics.Elapsed,
        ),
    ),

    Run(
        label = Txt("RUN", "BIEG"),
        usesStepSensor = true,
        thresholds = Thresholds(maxAccuracyM = 20f, minSpeedMps = 0.8f, maxSpeedMps = 8f),
        hero = true,
        metrics = listOf(
            Metrics.Distance,
            Metrics.Elapsed,
            Metrics.AvgPace,
        ),
    ),

    Walk(
        label = Txt("WALK", "PIESZO"),
        usesStepSensor = true,
        thresholds = Thresholds(maxAccuracyM = 15f, minSpeedMps = 0.4f, maxSpeedMps = 3f),
        hero = true,
        metrics = listOf(
            Metrics.Steps,
            Metrics.Elapsed,
            Metrics.Distance,
        ),
    ),
}

/** Tempo mm:ss na kilometr. Wolniej niż 30:00 to już nie ruch, tylko szum. */
private fun formatPace(speedMps: Float): String {
    if (speedMps <= 1000f / (30 * 60)) return "--:--"
    val total = (1000f / speedMps).toInt()
    return fmt("%2d:%02d", total / 60, total % 60)
}

/** Czas zawsze jako H:MM:SS — po pierwszej godzinie wiersz nie podskoczy. */
private fun clock(ms: Long): String {
    val s = ms / 1000
    return fmt("%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
}

/**
 * Formatowanie liczb na ekranie (zasada z fazy 1).
 *
 * Locale.US niezależnie od telefonu: polski locale daje przecinek, a wtedy
 * liczba na ekranie różni się od tej samej liczby w eksporcie. Stałe
 * szerokości ("%6.2f") trzymają wiersz w miejscu, kiedy liczba zmienia
 * rząd wielkości.
 */
internal fun fmt(format: String, vararg args: Any?): String =
    String.format(Locale.US, format, *args)
