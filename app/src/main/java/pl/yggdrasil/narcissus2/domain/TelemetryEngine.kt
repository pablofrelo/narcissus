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

    /**
     * Tempo z samego dopplera skacze co sekundę o kilkanaście sekund na
     * kilometrze — przy bieganiu nieczytelne. Pokazujemy tempo z dystansu
     * przebytego w ostatnich [PACE_WINDOW_MS], więc cyfra się uspokaja.
     */
    private val paceWindow = ArrayDeque<Pair<Long, Double>>()

    /** Bieżący kilometr: numer, dystans i czas ruchu w chwili jego rozpoczęcia. */
    private var lapIndex = 1
    private var lapStartDistM = 0.0
    private var lapStartMovingMs = 0L

    /**
     * Ruch z ostatniego ZAAKCEPTOWANEGO fixa. Pojedynczy odrzucony pomiar
     * nie może zerować tempa na ekranie — mrugałoby "--:--" w biegu.
     */
    private var lastMoving = false

    /** Przyrost kroków w czasie — z niego kadencja. */
    private val stepWindow = ArrayDeque<Pair<Long, Int>>()

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
        paceWindow.clear()
        lapIndex = 1
        lapStartDistM = 0.0
        lapStartMovingMs = 0L
        lastMoving = false
        stepWindow.clear()
    }

    /**
     * Czujnik kroków zwraca licznik od ostatniego restartu telefonu, nie od
     * startu przejazdu — zapamiętujemy punkt odniesienia przy pierwszym
     * odczycie i dalej liczymy różnicę.
     */
    @Synchronized
    fun onStepCounter(total: Int, nowMs: Long) {
        val base = stepsAtStart ?: total.also { stepsAtStart = it }
        steps = (total - base).coerceAtLeast(0)

        stepWindow.addLast(nowMs to steps)
        while (stepWindow.size > 2 && nowMs - stepWindow.first().first > CADENCE_WINDOW_MS) {
            stepWindow.removeFirst()
        }
    }

    // Kroki przychodzą z innego wątku niż pozycja (Dispatchers.Default),
    // a oba strumienie zmieniają ten sam stan — stąd @Synchronized.
    @Synchronized
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

        lastMoving = moving
        val movingBefore = movingMs
        if (moving) {
            movingMs += dtMs
            if (speed > maxSpeedMps) maxSpeedMps = speed
        }
        val distBefore = distanceM

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

        closeLaps(distBefore, movingBefore)

        paceWindow.addLast(nowMs to distanceM)
        while (paceWindow.size > 2 && nowMs - paceWindow.first().first > PACE_WINDOW_MS) {
            paceWindow.removeFirst()
        }

        return snapshot(nowMs, accuracy, warming = false, moving = moving, speed = speed)
    }

    /**
     * Przekroczenie pełnego kilometra zamyka okrążenie. Dystans rośnie
     * skokami (patrz zaczepienie), więc moment przekroczenia leży gdzieś
     * wewnątrz ostatniego skoku — czas ruchu interpolujemy liniowo, zamiast
     * doliczać cały skok do starego albo nowego kilometra.
     */
    private fun closeLaps(distBefore: Double, movingBefore: Long) {
        while (distanceM >= lapIndex * 1000.0) {
            val boundary = lapIndex * 1000.0
            val span = distanceM - distBefore
            val f = if (span > 0) ((boundary - distBefore) / span).coerceIn(0.0, 1.0) else 1.0
            lapStartMovingMs = movingBefore + ((movingMs - movingBefore) * f).toLong()
            lapStartDistM = boundary
            lapIndex++
        }
    }

    /** Tempo z ostatnich sekund; 0 na postoju. */
    private fun smoothSpeed(moving: Boolean): Float {
        if (!moving || paceWindow.size < 2) return 0f
        val (t0, d0) = paceWindow.first()
        val (t1, d1) = paceWindow.last()
        val dt = (t1 - t0) / 1000.0
        return if (dt >= 3.0) ((d1 - d0) / dt).toFloat() else 0f
    }

    /**
     * Tempo bieżącego kilometra liczone z czasu RUCHU — postój na światłach
     * go nie psuje. Przez pierwsze [LAP_MIN_M] kilometra dzielenie przez
     * kilkadziesiąt metrów daje bzdury, więc do tego czasu pokazujemy tempo
     * wygładzone.
     */
    private fun lapSpeed(smooth: Float): Float {
        val d = distanceM - lapStartDistM
        val s = (movingMs - lapStartMovingMs) / 1000.0
        return if (d >= LAP_MIN_M && s > 0) (d / s).toFloat() else smooth
    }

    /** Kroki na minutę z ostatnich sekund. Brak nowych kroków = 0. */
    private fun cadence(nowMs: Long): Int? {
        if (stepsAtStart == null) return null
        if (stepWindow.size < 2) return 0
        val (t0, s0) = stepWindow.first()
        val (t1, s1) = stepWindow.last()
        if (nowMs - t1 > CADENCE_STALE_MS) return 0
        val dt = t1 - t0
        return if (dt >= 5_000) ((s1 - s0) * 60_000L / dt).toInt() else 0
    }

    /** Wywoływane co sekundę, żeby czas leciał także bez nowego fixa. */
    @Synchronized
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
        val smooth = smoothSpeed(lastMoving)

        return Telemetry(
            distanceM = distanceM,
            elapsedMs = nowMs - startedAt,
            movingMs = movingMs,
            speedMps = if (moving) speed else 0f,
            avgMovingSpeedMps = if (movingS > 0) (distanceM / movingS).toFloat() else 0f,
            maxSpeedMps = maxSpeedMps,
            paceSpeedMps = smooth,
            lapIndex = lapIndex,
            lapSpeedMps = if (lastMoving) lapSpeed(smooth) else 0f,
            totalSteps = steps,
            cadenceSpm = cadence(nowMs),
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

        /** Okno wygładzania tempa. Krócej skacze, dłużej spóźnia się na zmiany. */
        private const val PACE_WINDOW_MS = 20_000L

        /** Od tylu metrów kilometra jego tempo liczy się z niego samego. */
        private const val LAP_MIN_M = 100.0

        /**
         * Licznik kroków oddaje zdarzenia paczkami, co kilka sekund —
         * krótsze okno dawałoby kadencję skaczącą między zerem a dwustoma.
         */
        private const val CADENCE_WINDOW_MS = 20_000L
        private const val CADENCE_STALE_MS = 6_000L
    }
}
