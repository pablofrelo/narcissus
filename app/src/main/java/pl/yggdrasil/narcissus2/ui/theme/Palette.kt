package pl.yggdrasil.narcissus2.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit
import pl.yggdrasil.narcissus2.R

/**
 * Kolor nie mieszka w komponencie. Komponent zna tylko ROLĘ — "readout",
 * "chrome", "alarm" — a motyw podstawia pod rolę wartość.
 *
 * Dzięki temu dodanie drugiego motywu to jeden obiekt [Palette] i zero
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

/**
 * NOSTROMO — kolory ze strony pablofrelo.github.io, 1:1 z CSS.
 * Jedyny motyw: na Nostromo nie ma dnia.
 */
val NostromoPalette = Palette(
    void = Color(0xFF050806),
    hull = Color(0xFF0A120C),
    grid = Color(0xFF163D22),
    dim = Color(0xFF4F9A68),
    phosphor = Color(0xFF8CFFB0),
    readout = Color(0xFFD4FFE0),
    amber = Color(0xFFFFB000),
    alarm = Color(0xFFFF5A4A),
)

/**
 * Siatka Spleen, dobrana dla ekranu 1080 px (jak w fazie 1). Na innych
 * szerokościach [gridSp] przeliczy ją proporcjonalnie.
 *
 * Spleen jest krojem BITMAPOWYM: wersji OpenType trzeba używać w pełnych
 * wielokrotnościach rozmiaru, w jakim autor go narysował. Stąd dwa warianty
 * i wartości w pikselach, nie w sp.
 */
object Grid {
    /** Wielka cyfra, wariant 32×64. */
    const val READOUT = 352
    /** Liczby w komórkach. */
    const val VALUE = 128
    /** Przyciski. */
    const val COMMAND = 96
    /** Etykiety, status, nagłówek. */
    const val LABEL = 64
    /** Jednostki i odczyty wskaźników. */
    const val UNIT = 64
    /** Wartości w listach (dziennik, panel pozycji). */
    const val VALUE_SMALL = 64

    /** Od tego rozmiaru bierzemy wariant 32×64. */
    const val LARGE_FROM = 256
}

/** Szerokość ekranu, dla której dobrano wartości w [Grid]. */
private const val REFERENCE_WIDTH_PX = 1080

/**
 * Rozmiar z siatki, przeskalowany do szerokości ekranu i przyciągnięty
 * do komórki kroju.
 *
 * Dlaczego nie zwykłe sp: skalowanie ciągłe rozmywa bitmapę. Dlaczego nie
 * stałe piksele: 352 px cyfry to jedna trzecia szerokości na 1080 px, ale
 * połowa na 720 px. Skalujemy więc proporcjonalnie i zaokrąglamy W DÓŁ do
 * pełnej komórki (32 px albo 64 px) — rozmiar jest zawsze całkowitą krotnością
 * tego, co narysował autor, więc zostaje ostry na każdym telefonie.
 *
 * Systemowe powiększenie tekstu celowo nie ma tu wpływu: licznik ma wyglądać
 * tak samo niezależnie od ustawień telefonu i nie rozsadzać układu.
 */
@Composable
@ReadOnlyComposable
fun gridSp(pixels: Int): TextUnit {
    val density = LocalDensity.current
    val screenPx = LocalConfiguration.current.screenWidthDp * density.density

    val cell = if (pixels >= Grid.LARGE_FROM) 64 else 32
    val scaled = (pixels * screenPx / REFERENCE_WIDTH_PX).toInt()
    val snapped = ((scaled / cell) * cell).coerceAtLeast(cell)

    return with(density) { snapped.toSp() }
}

/**
 * Spleen — font konsoli OpenBSD (Frederic Cambus, BSD 2-Clause).
 *
 * UWAGA: wymaga plików z fazy 1:
 *   app/src/main/res/font/spleen_16x32.otf
 *   app/src/main/res/font/spleen_32x64.otf
 */
object Type {
    /** 16×32 — etykiety, liczby w komórkach, przyciski. */
    val Chrome: FontFamily = FontFamily(Font(R.font.spleen_16x32))
    val Readout: FontFamily = FontFamily(Font(R.font.spleen_16x32))

    /** 32×64 — wielka cyfra. */
    val ReadoutLarge: FontFamily = FontFamily(Font(R.font.spleen_32x64))

    fun forSize(pixels: Int): FontFamily =
        if (pixels >= Grid.LARGE_FROM) ReadoutLarge else Readout
}

val LocalPalette = staticCompositionLocalOf { NostromoPalette }

@Composable
fun NarcissusTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalPalette provides NostromoPalette, content = content)
}

object Theme {
    val palette: Palette
        @Composable @ReadOnlyComposable get() = LocalPalette.current
}
