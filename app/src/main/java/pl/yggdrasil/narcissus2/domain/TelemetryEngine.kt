package pl.yggdrasil.narcissus2.domain

import android.location.Location
import kotlin.math.max

/**
 * Silnik pomiarowy. Czysta logika, zero Androida poza klasą [Location] —
 * dzięki temu da się to przetestować bez telefonu.
 *
 * Cała trudność licznika GPS siedzi w jednym miejscu: sygnał szumi, a naiwne
 * sumowanie odległości między kolejnymi fixami nabija kilometry na postoju.
 * Filtrujemy więc na trzech poziomach: jakość fixa, wiarygodność przeskoku,
 * odejście od punktu zaczepienia.
 */
class TelemetryEngine(
    private val mode: ActivityMode,
) {
    private var startedAt = 0L

    /** Ostatni fix — służy do liczenia prędkości między pomiarami. */
    private var lastFix: Location? = null
    private var lastFixAt = 0L

    /**
     * Punkt zaczepienia: ostatnia pozycja, od której zatwierdziliśmy dystans.
     *
     * TO JEST SEDNO. Próg szumu (kilka metrów) NIE może być sprawdzany na
     * pojedynczym fixie, bo przy pomiarach co sekundę marsz daje przesunięcie
     * rzędu półtora metra i nic nigdy nie przechodzi — licznik stoi na zerze
     * przez cały spacer. Zamiast tego przesunięcie kumuluje się względem
     * zaczepienia i dopiero po przekroczeniu szumu trafia do dystansu,
     * a zaczepienie przeskakuje na bieżącą pozycję.
     *
     * Efekt uboczny jest pożądany: dryf na postoju krąży wokół zaczepienia
     * i nigdy się od niego nie oddala, więc nadal nic nie nabija.
     */
    private var anchor: Location? = null

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
        anchor = null
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
            if (!warming) anchor = fix
            accepted++
            return snapshot(nowMs, accuracy, warming, moving = false)
        }

        val step = previous.distanceTo(fix).toDouble()
        val dtS = dtMs / 1000.0
        val impliedSpeed = (step / dtS).toFloat()

        // --- poziom 2: wiarygodność przeskoku ---
        // Przeskok szybszy niż fizycznie możliwy dla trybu to artefakt,
        // nie ruch. Częste przy wychodzeniu spod wiaduktu.
        if (impliedSpeed > mode.thresholds.maxSpeedMps) {
            rejected++
            // Fix zapamiętujemy mimo odrzucenia — inaczej następny pomiar
            // policzyłby dystans względem pozycji sprzed kilku minut.
            lastFix = fix
            lastFixAt = nowMs
            anchor = fix
            return snapshot(nowMs, accuracy, warming, moving = false)
        }

        // Prędkość chwilowa: wolimy tę z odbiornika, bo pochodzi z dopplera
        // i jest wyraźnie stabilniejsza niż różniczkowanie pozycji.
        val speed = if (fix.hasSpeed() && fix.speed > 0f) fix.speed else impliedSpeed
        val moving = speed >= mode.thresholds.minSpeedMps

        lastFix = fix
        lastFixAt = nowMs
        accepted++

        if (warming) {
            anchor = fix
            return snapshot(nowMs, accuracy, warming = true, moving = false)
        }

        if (moving) {
            movingMs += dtMs
            if (speed > maxSpeedMps) maxSpeedMps = speed
        }

        // --- poziom 3: odejście od zaczepienia ---
        val base = anchor
        if (base == null) {
            anchor = fix
        } else {
            val fromAnchor = base.distanceTo(fix).toDouble()
            val floor = max(accuracy * 0.5, MIN_FLOOR_M)

            if (fromAnchor > floor) {
                distanceM += fromAnchor
                anchor = fix
            }
        }

        return snapshot(nowMs, accuracy, warming = false, moving = moving, speed = speed)
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

    companion object {
        /**
         * Dolna granica progu szumu.
         *
         * Przy bardzo dobrym fixie (dokładność 2 m) połowa dokładności dałaby
         * metr, co jest poniżej realnego rozrzutu odbiornika. Trzy metry to
         * kompromis: marsz przekracza je w dwie sekundy, a dryf na postoju
         * rzadko wychodzi tak daleko od zaczepienia.
         */
        private const val MIN_FLOOR_M = 3.0
    }
}
