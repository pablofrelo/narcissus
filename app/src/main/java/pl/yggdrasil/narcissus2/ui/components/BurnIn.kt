package pl.yggdrasil.narcissus2.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin

/**
 * Ochrona przed wypaleniem matrycy AMOLED.
 *
 * Ekran świeci teraz bez przerwy, a układ jest nieruchomy: te same etykiety
 * w tych samych miejscach, godzinami. Na OLED-zie każdy subpiksel starzeje
 * się proporcjonalnie do tego, ile świecił — po kilkudziesięciu godzinach
 * napis PRĘDKOŚĆ zostaje w matrycy na stałe i widać go na białym tle.
 *
 * Czarne tło samo w sobie bardzo pomaga (wygaszone piksele nie pobierają
 * prądu i się nie starzeją), więc zagrożone są tylko jasne, nieruchome
 * elementy — etykiety i duża cyfra.
 *
 * Lekarstwo: cała treść powoli wędruje po ekranie. Kilka pikseli wystarczy,
 * żeby rozłożyć zużycie na większy obszar, a przy takim tempie ruch jest
 * niezauważalny.
 */
data class BurnInShift(val dx: Dp, val dy: Dp)

/**
 * Pozycja liczona z dwóch sinusów o NIEWSPÓŁMIERNYCH okresach.
 *
 * Gdyby okresy były w prostym stosunku, treść krążyłaby po zamkniętej
 * pętli i wracała dokładnie w te same punkty — czyli wypalałaby tę pętlę
 * zamiast pojedynczych miejsc. Niewspółmierne okresy dają tor, który przez
 * bardzo długi czas się nie powtarza.
 *
 * Przesunięcie aktualizujemy co kilka sekund, nie co klatkę. Ruch rzędu
 * piksela na kilkanaście sekund i tak jest niewidoczny, a przeliczanie
 * układu sześćdziesiąt razy na sekundę byłoby marnowaniem baterii
 * dokładnie tam, gdzie jej oszczędzamy.
 */
@Composable
fun rememberBurnInShift(
    amplitude: Dp = 8.dp,
    periodMs: Long = 210_000,
    stepMs: Long = 5_000,
): BurnInShift {
    var phase by remember { mutableStateOf(0L) }

    LaunchedEffect(Unit) {
        val startedAt = System.currentTimeMillis()
        while (true) {
            phase = System.currentTimeMillis() - startedAt
            delay(stepMs)
        }
    }

    val t = phase.toDouble()

    // Złota proporcja jako stosunek okresów — najgorzej przybliżalna
    // liczba wymierna, więc tor zamyka się najpóźniej jak to możliwe.
    val fx = sin(2 * PI * t / periodMs)
    val fy = sin(2 * PI * t / (periodMs * 1.618))

    // Sinus daje zakres -1..1, my potrzebujemy 0..2A, żeby suma marginesów
    // pozostała stała i nic nie wyjechało poza ekran.
    return BurnInShift(
        dx = amplitude * ((fx + 1) / 2).toFloat(),
        dy = amplitude * ((fy + 1) / 2).toFloat(),
    )
}

/**
 * Margines, który wędruje, zachowując stałą sumę.
 *
 * Nie używamy tu offsetu, bo przesunięcie całej treści wypchnęłoby ją poza
 * krawędź po jednej stronie. Zamiast tego zabieramy z jednego marginesu
 * dokładnie tyle, ile dokładamy do drugiego — szerokość treści nie zmienia
 * się ani o piksel, więc nic się nie przewija i nie skacze.
 */
fun Modifier.burnInPadding(
    horizontal: Dp,
    vertical: Dp,
    shift: BurnInShift,
    amplitude: Dp = 8.dp,
): Modifier = this.padding(
    PaddingValues(
        start = horizontal - amplitude / 2 + shift.dx / 2,
        end = horizontal + amplitude / 2 - shift.dx / 2,
        top = vertical - amplitude / 2 + shift.dy / 2,
        bottom = vertical + amplitude / 2 - shift.dy / 2,
    ),
)
