package pl.yggdrasil.narcissus2.domain

import java.util.UUID

/**
 * Zakończona sesja.
 *
 * [id] nadaje URZĄDZENIE przy tworzeniu, nie serwer. To jest warunek
 * poprawnej synchronizacji: bez tego "ta sama sesja" znaczy co innego po
 * każdej stronie i wszystko zależy od kolejności wysyłki.
 *
 * [deletedAt] to nagrobek. Skasowanie NIE usuwa rekordu, tylko go oznacza —
 * inaczej sync widzi brak rekordu i nie umie odróżnić "skasowane" od
 * "urządzenie jeszcze tego nie ma", więc grzecznie przywraca. Dokładnie to
 * stało się w pierwszej budowie z sesjami testowymi.
 */
data class Session(
    val id: String = UUID.randomUUID().toString(),
    val mode: ActivityMode,
    val startedAt: Long,
    val endedAt: Long,

    val distanceM: Double,
    val elapsedMs: Long,
    val movingMs: Long,
    val avgMovingSpeedMps: Float,
    val maxSpeedMps: Float,
    val totalSteps: Int,
    val acceptedFixes: Int,
    val rejectedFixes: Int,

    /** Przewyższenie dolicza heimdall z modelu terenu. Null do czasu synchronizacji. */
    val ascentM: Double? = null,

    /** Ile punktów ma ślad. Sam ślad leży w osobnym pliku. */
    val pointCount: Int = 0,

    /**
     * Sesja testowa. Oznaczona przy tworzeniu, żeby "skasuj wszystkie
     * testowe" było jednym kliknięciem, a nie polowaniem po dzienniku.
     */
    val test: Boolean = false,

    /** Znacznik ostatniej modyfikacji — rozstrzyga konflikty przy synchronizacji. */
    val updatedAt: Long = System.currentTimeMillis(),

    /** Nagrobek. Niepusty znaczy: skasowana, ale rekord musi dojechać do serwera. */
    val deletedAt: Long? = null,

    /** Czy serwer potwierdził odbiór tej wersji rekordu. */
    val synced: Boolean = false,
) {
    val alive: Boolean get() = deletedAt == null
}

/** Pojedynczy punkt śladu. */
data class TrackPoint(
    val timestamp: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracyM: Float,
    val speedMps: Float,
    /** Surowa wysokość z GNSS — orientacyjna, patrz [AltitudeSource]. */
    val altitudeM: Double?,
)
