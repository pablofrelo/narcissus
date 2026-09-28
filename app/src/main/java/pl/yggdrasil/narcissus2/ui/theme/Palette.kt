package pl.yggdrasil.narcissus2.ui.theme

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit
import pl.yggdrasil.narcissus2.R
import kotlin.math.roundToInt

/**
 * Kolor nie mieszka w komponencie. Komponent zna tylko ROLĘ — "readout",
 * "chrome", "alarm" — a motyw podstawia pod rolę wartość.
 *
 * Dzięki temu dodanie trzeciego motywu to jeden obiekt [Palette] i zero
 * zmian w widokach.
 */
data class Palette(
    val void: Color,
    val hull: Color,
    val grid: Color,
    val dim: Color,
    val phosphor: Color,
    val readout: Color,
    val amber: Color,
    val alarm: Color,
)

/** NOC — zielony fosfor na czerni absolutnej (OLED nie zapala pikseli). */
val NightPalette = Palette(
    void = Color(0xFF000000),
    hull = Color(0xFF071008),
    grid = Color(0xFF143D20),
    dim = Color(0xFF2E8B4F),
    phosphor = Color(0xFF3FE06B),
    readout = Color(0xFFB6FFCC),
    amber = Color(0xFFFFB000),
    alarm = Color(0xFFFF3B30),
)

/**
 * DZIEŃ — ekri jak papier z drukarki igłowej, atrament prawie czarny.
 *
 * To NIE jest odwrócony motyw nocny: zielony fosfor na beżu byłby
 * nieczytelny. W pełnym słońcu liczy się wyłącznie kontrast, więc rolę
 * "readout" gra tu najciemniejszy kolor, nie najjaśniejszy.
 */
val DayPalette = Palette(
    void = Color(0xFFE9E2CE),
    hull = Color(0xFFDED5BD),
    grid = Color(0xFFA89E84),
    dim = Color(0xFF6B6250),
    phosphor = Color(0xFF2A2620),
    readout = Color(0xFF14110C),
    amber = Color(0xFF9A5B00),
    alarm = Color(0xFFA11208),
)

/**
 * Rozmiary — wszystkie wielokrotności 11.
 *
 * Departure Mono jest fontem pikselowym: ostre krawędzie wychodzą tylko
 * przy rozmiarach będących wielokrotnością siatki. Poza nią renderer
 * interpoluje i piksele się rozmywają.
 */
object Grid {
    const val LABEL = 11
    const val UNIT = 11
    const val VALUE = 33
    const val READOUT = 66
    const val COMMAND = 22

    /** Szerokość, pod którą układ był projektowany (S20 FE, w dp). */
    const val DESIGN_WIDTH_DP = 411f
}

/**
 * Skala siatki: szerokość ekranu względem [Grid.DESIGN_WIDTH_DP].
 * Węższy telefon dostaje mniejsze cyfry, szerszy większe — proporcje
 * między etykietą a odczytem zostają te same.
 */
val LocalGridScale = staticCompositionLocalOf { 1f }

/**
 * Rozmiar czcionki z siatki, gotowy do [androidx.compose.material3.Text].
 *
 * Dwie rzeczy naraz:
 *  - wynik zaokrąglamy do pełnej wielokrotności 11 FIZYCZNYCH pikseli,
 *    bo tylko wtedy piksel fontu trafia w piksel ekranu i nic się nie rozmywa;
 *  - systemowe powiększenie tekstu jest ignorowane. Tu każdy rozmiar jest
 *    dobrany do siatki, a powiększona cyfra po prostu nie zmieściłaby się
 *    w wierszu. Liczymy więc w pikselach, nie w sp.
 */
@Composable
@ReadOnlyComposable
fun gridSp(size: Int): TextUnit {
    val density = LocalDensity.current
    val scale = LocalGridScale.current
    val px = ((size * density.density * scale) / 11f).roundToInt().coerceAtLeast(1) * 11
    return with(density) { px.toSp() }
}

/**
 * Kroje pisma.
 *
 * Rozdzielone na dwa pola mimo tej samej wartości — kiedyś możesz chcieć
 * cieńszego kroju na etykiety, a rozdzielenie teraz nic nie kosztuje.
 *
 * UWAGA: zakłada plik app/src/main/res/font/departure_mono.ttf (albo .otf).
 * Jeśli nazwałeś go inaczej, popraw referencję poniżej.
 */
object Type {
    val Readout: FontFamily = FontFamily(Font(R.font.departure_mono))
    val Chrome: FontFamily = FontFamily(Font(R.font.departure_mono))
}

val LocalPalette = staticCompositionLocalOf { NightPalette }

@Composable
fun NarcissusTheme(
    day: Boolean = false,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints {
        val scale = (maxWidth.value / Grid.DESIGN_WIDTH_DP).coerceIn(0.7f, 1.6f)

        CompositionLocalProvider(
            LocalPalette provides if (day) DayPalette else NightPalette,
            LocalGridScale provides scale,
            content = content,
        )
    }
}

object Theme {
    val palette: Palette
        @Composable @ReadOnlyComposable get() = LocalPalette.current
}
