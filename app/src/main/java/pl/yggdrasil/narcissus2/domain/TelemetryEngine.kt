package pl.yggdrasil.narcissus2.domain

import android.location.Location
import kotlin.math.max

/**
 * Silnik pomiarowy. Czysta logika, zero Androida poza klasą [Location] —
 * dzięki temu da się to przetestować bez telefonu.
 *
 * Cała trudność licznika GPS siedzi w jednym miejscu: sygnał szumi, a
 * naiwne sumowanie odległości między kolejnymi fixami nabija kilometry
 * podczas postoju na światłach. Filtrujemy więc na trzech poziomach:
 * jakość fixa, wiarygodność przeskoku, minimalne przemieszczenie.
 */
class TelemetryEngine(
    private val mode: ActivityMode,
) {
    private var startedAt = 0L
    private var lastFix: Location? = null
    private var lastFixAt = 0L

    private var distanceM = 0.0
    private var movingMs = 0L
    private var maxSpeedMps = 0f

    private var accepted = 0
    private var rejected = 0

    private var stepsAtStart: Int? = null
    private var steps = 0

    /** Pierwsze sekundy odrzucamy — świeży fix potrafi skoczyć o kilkadziesiąt metrów. */
    private val warmupMs = 8_000L

    fun start(nowMs: Long) {
        startedAt = nowMs
        lastFix = null
        lastFixAt = 0L
        distanceM = 0.0
        movingMs = 0L
        maxSpeedMps = 0f
        accepted = 0
        rejected = 0
        stepsAtStart = null
        steps = 0
    }

    /**
     * Czujnik kroków zwraca licznik od ostatniego restartu telefonu, nie od
     * startu przejazdu — zapamiętujemy punkt odniesienia przy pierwszym
     * odczycie i dalej liczymy różnicę.
     */
    fun onStepCounter(total: Int) {
        val base = stepsAtStart ?: total.also { stepsAtStart = it }
        steps = (total - base).coerceAtLeast(0)
    }

    /**
     * @return telemetria po uwzględnieniu fixa. Fix odrzucony też zwraca
     *         stan — licznik POMIARY ma pokazywać, ile odpadło.
     */
    fun onLocation(fix: Location, nowMs: Long): Telemetry {
        val warming = nowMs - startedAt < warmupMs
        val accuracy = if (fix.hasAccuracy()) fix.accuracy else Float.MAX_VALUE

        // --- poziom 1: jakość fixa ---
        if (accuracy > mode.thresholds.maxAccuracyM) {
            rejected++
            return snapshot(nowMs, accuracy, warming, moving = false)
        }

        val previous = lastFix
        val dtMs = nowMs - lastFixAt

        if (previous == null || dtMs <= 0) {
            lastFix = fix
            lastFixAt = nowMs
            accepted++
            return snapshot(nowMs, accuracy, warming, moving = false)
        }

        val delta = previous.distanceTo(fix).toDouble()
        val dtS = dtMs / 1000.0
        val impliedSpeed = (delta / dtS).toFloat()

        // --- poziom 2: wiarygodność przeskoku ---
        // Przeskok szybszy niż fizycznie możliwy dla trybu to artefakt,
        // nie ruch. Częste przy wychodzeniu spod wiaduktu.
        if (impliedSpeed > mode.thresholds.maxSpeedMps) {
            rejected++
            // Fix zapamiętujemy mimo odrzucenia — inaczej następny pomiar
            // policzy dystans względem pozycji sprzed kilku minut.
            lastFix = fix
            lastFixAt = nowMs
            return snapshot(nowMs, accuracy, warming, moving = false)
        }

        // --- poziom 3: minimalne przemieszczenie ---
        // Dopóki przesunięcie mieści się w błędzie pomiaru, to jest szum,
        // nie ruch. Bez tego progu licznik rośnie na postoju.
        val floor = max(accuracy * 0.5, 2.0)
        val moving = impliedSpeed >= mode.thresholds.minSpeedMps && delta > floor

        if (moving && !warming) {
            distanceM += delta
            movingMs += dtMs
            if (impliedSpeed > maxSpeedMps) maxSpeedMps = impliedSpeed
        }

        lastFix = fix
        lastFixAt = nowMs
        accepted++

        return snapshot(
            nowMs = nowMs,
            accuracy = accuracy,
            warming = warming,
            moving = moving,
            // Prędkość chwilowa: wolimy tę z odbiornika, bo pochodzi
            // z dopplera i jest dokładniejsza niż różniczkowanie pozycji.
            speed = if (fix.hasSpeed()) fix.speed else impliedSpeed,
        )
    }

    /** Wywoływane co sekundę, żeby czas leciał także bez nowego fixa. */
    fun tick(nowMs: Long): Telemetry =
        snapshot(nowMs, lastFix?.accuracy, nowMs - startedAt < warmupMs, moving = false)

    private fun snapshot(
        nowMs: Long,
        accuracy: Float?,
        warming: Boolean,
        moving: Boolean,
        speed: Float = 0f,
    ): Telemetry {
        val movingS = movingMs / 1000.0

        return Telemetry(
            distanceM = distanceM,
            elapsedMs = nowMs - startedAt,
            movingMs = movingMs,
            speedMps = if (moving) speed else 0f,
            avgMovingSpeedMps = if (movingS > 0) (distanceM / movingS).toFloat() else 0f,
            maxSpeedMps = maxSpeedMps,
            totalSteps = steps,
            cadenceSpm = null,
            acceptedFixes = accepted,
            rejectedFixes = rejected,
            lastAccuracyM = accuracy,
            moving = moving,
            warmingUp = warming,
            rawAltitudeM = null,
            ascentM = null,
        )
    }
}
