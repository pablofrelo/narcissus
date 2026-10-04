package pl.yggdrasil.narcissus2.domain

/**
 * Na jaki tryb wyglądają liczby zakończonej sesji. Null, gdy nie da się
 * tego powiedzieć z rozsądną pewnością — wtedy lepiej nic nie podpowiadać.
 *
 * Patrzy na prędkość w ruchu i kadencję. Kroki liczą się w każdym trybie,
 * także na rowerze, gdzie po prostu ich nie widać — bez nich spaceru
 * w trybie ROWER nie dałoby się odróżnić od wolnej jazdy.
 */
fun guessMode(s: Session): ActivityMode? {
    val movingMin = s.movingMs / 60_000.0
    if (movingMin < 3.0) return null

    val kmh = s.avgMovingSpeedMps * 3.6
    val hasSteps = s.totalSteps > 0
    val cadence = s.totalSteps / movingMin

    // Za szybko dla trybu: większość fixów poszła do kosza jako nierealne
    // przeskoki, np. rower w trybie PIESZO, gdzie limit to 3 m/s.
    val choked = s.rejectedFixes > s.acceptedFixes

    return when {
        hasSteps && cadence >= 140 && kmh >= 6.5 -> ActivityMode.Run
        hasSteps && cadence >= 70 && kmh < 7.0 -> ActivityMode.Walk
        (!hasSteps || cadence < 30) && (kmh >= 12.0 || choked) -> ActivityMode.Bike
        !hasSteps && kmh < 6.0 -> ActivityMode.Walk
        else -> null
    }
}
