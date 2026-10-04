package pl.yggdrasil.narcissus2.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import pl.yggdrasil.narcissus2.i18n.tr
import pl.yggdrasil.narcissus2.system.SystemStatus
import pl.yggdrasil.narcissus2.ui.theme.Grid
import pl.yggdrasil.narcissus2.ui.theme.Theme
import pl.yggdrasil.narcissus2.ui.theme.Type
import pl.yggdrasil.narcissus2.ui.theme.gridSp
import kotlinx.coroutines.delay
import kotlin.math.abs

/** Progi termiczne. Powyżej 40 stopni telefon zaczyna dławić taktowanie. */
private const val TEMP_WARN = 40f
private const val TEMP_CRITICAL = 45f

/**
 * Wskaźniki podsystemów na lewej krawędzi, na całą wysokość panelu.
 *
 * Wszystkie odczyty numeryczne są wyrównane do prawej, więc lewa krawędź
 * i tak stoi pusta. Wskaźniki wchodzą w to miejsce i nie kosztują ani
 * jednego piksela wysokości — a trafiają tam, gdzie ląduje wzrok przy
 * zerknięciu na dużą cyfrę.
 */
private data class IndicatorSpec(
    val tag: String,
    val level: Int,
    val readout: String,
    val tint: Color,
    val onTap: (() -> Unit)? = null,
)

/** Panel szczegółów pod wskaźnikiem, który dotknięto. */
enum class Detail { GPS, GSM, PWR }

/** Trzy wskaźniki podsystemów — wspólne dane dla kolumny i paska. */
@Composable
private fun indicatorSpecs(status: SystemStatus, onTap: (Detail) -> Unit): List<IndicatorSpec> {
    val p = Theme.palette
    val temp = status.battery.temperatureC

    // Przegrzanie wypiera procenty. Telefon gotujący się na słońcu to realna
    // awaria: najpierw dławi taktowanie, potem system ubija aplikację.
    // Rozładowanie da się przewidzieć, przegrzanie przychodzi znienacka,
    // więc ma pierwszeństwo.
    val overheating = temp != null && temp >= TEMP_WARN

    return listOf(
        IndicatorSpec(
            tag = "GPS",
            level = status.gnss.level,
            readout = if (status.gnss.hasFix) {
                "${status.gnss.usedInFix}/${status.gnss.visible}"
            } else {
                "----"
            },
            tint = if (status.gnss.hasFix) p.phosphor else p.amber,
            onTap = { onTap(Detail.GPS) },
        ),
        IndicatorSpec(
            tag = "GSM",
            level = status.cellular.level.coerceAtLeast(0),
            readout = status.cellular.dbm?.toString() ?: "----",
            tint = when {
                status.cellular.level < 0 -> p.dim
                status.cellular.level <= 1 -> p.amber
                else -> p.phosphor
            },
            onTap = { onTap(Detail.GSM) },
        ),
        IndicatorSpec(
            tag = if (overheating) "TMP" else "PWR",
            level = if (overheating) thermalLevel(temp!!) else status.battery.level,
            readout = when {
                overheating -> "%.0f\u00B0".format(temp)
                status.battery.percent >= 0 -> "${status.battery.percent}%"
                else -> "--%"
            },
            tint = when {
                temp != null && temp >= TEMP_CRITICAL -> p.alarm
                overheating -> p.amber
                status.battery.critical -> p.alarm
                status.battery.level <= 1 -> p.amber
                else -> p.phosphor
            },
            onTap = { onTap(Detail.PWR) },
        ),
    )
}

/**
 * Wskaźniki w jednym rzędzie, na całą szerokość.
 *
 * Przy siatce komórek po dwie w rzędzie (układ z fazy 1) kolumna z lewej
 * zabierałaby szerokość liczbom — dlatego wskaźniki idą w pasek nad nimi.
 */
@Composable
fun IndicatorStrip(
    status: SystemStatus,
    onTap: (Detail) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        indicatorSpecs(status, onTap).forEach { it.Draw(Modifier.weight(1f), compact = true) }
    }
}

@Composable
private fun IndicatorSpec.Draw(modifier: Modifier, compact: Boolean = false) {
    Indicator(
        tag = tag,
        level = level,
        readout = readout,
        tint = tint,
        compact = compact,
        modifier = onTap?.let { modifier.tap(it) } ?: modifier,
    )
}

/** Im goręcej, tym więcej segmentów — odwrotnie niż przy baterii. */
private fun thermalLevel(temp: Float): Int = when {
    temp >= 50f -> 4
    temp >= TEMP_CRITICAL -> 3
    temp >= 42f -> 2
    else -> 1
}

@Composable
private fun Indicator(
    tag: String,
    level: Int,
    readout: String,
    tint: Color,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val p = Theme.palette

    Column(modifier.fillMaxWidth()) {
        // W pasku etykieta i odczyt stoją w jednej linii — pasek ma być
        // niski, bo wysokość ekranu idzie na liczby.
        if (compact) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Label(tag, color = p.dim, softWrap = false)
                Text(
                    text = readout,
                    color = tint,
                    fontFamily = Type.Readout,
                    fontSize = gridSp(Grid.UNIT),
                    maxLines = 1,
                )
            }
        } else {
            Label(tag, color = p.dim, softWrap = false)

            Text(
                text = readout,
                color = tint,
                fontFamily = Type.Readout,
                fontSize = gridSp(Grid.UNIT),
                modifier = Modifier.padding(top = 2.dp),
            )
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            repeat(4) { i ->
                Box(
                    Modifier
                        .weight(1f)
                        .height(4.dp)
                        .background(if (i < level) tint else p.grid),
                )
            }
        }
    }
}

/**
 * Panel szczegółów pod paskiem wskaźników: GPS, GSM albo PWR, zależnie od
 * tego, który dotknięto.
 *
 * Trafia tu wszystko, co sprawdzasz sporadycznie i na postoju. Na stałym
 * widoku te dane tylko zabierałyby miejsce odczytom, na które patrzysz
 * w ruchu. Zwija się dotknięciem albo sam po [AUTO_CLOSE_MS], jak menu
 * w muthurze — otwarty panel nie może zostać na całą trasę.
 */
@Composable
fun DetailPanel(
    detail: Detail?,
    latitude: Double?,
    longitude: Double?,
    accuracyM: Float?,
    status: SystemStatus,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Ostatni otwarty panel zostaje w pamięci, żeby było co zwijać
    // w animacji wyjścia, gdy detail jest już null.
    val last = remember { arrayOf(Detail.GPS) }
    if (detail != null) last[0] = detail
    val shown = detail ?: last[0]

    // Każde otwarcie albo przełączenie na inny wskaźnik liczy od nowa.
    LaunchedEffect(detail) {
        if (detail != null) {
            delay(AUTO_CLOSE_MS)
            onDismiss()
        }
    }

    AnimatedVisibility(
        visible = detail != null,
        enter = expandVertically(),
        exit = shrinkVertically(),
    ) {
        Panel(modifier.tap(onDismiss)) {
            when (shown) {
                Detail.GPS -> GpsDetail(latitude, longitude, accuracyM, status.gnss)
                Detail.GSM -> GsmDetail(status.cellular)
                Detail.PWR -> PwrDetail(status.battery)
            }
        }
    }
}

private const val AUTO_CLOSE_MS = 5_000L

@Composable
private fun GpsDetail(
    latitude: Double?,
    longitude: Double?,
    accuracyM: Float?,
    gnss: SystemStatus.Gnss,
) {
    val p = Theme.palette

    if (latitude == null || longitude == null) {
        DetailRow(tr("LAT.", "SZER."), tr("NO FIX", "BRAK FIXA"), "", p.amber)
    } else {
        // Pięć miejsc po przecinku to około metra rozdzielczości.
        // DMS obok, bo mapy papierowe nadal go używają.
        DetailRow(tr("LAT.", "SZER."), "%.5f".format(latitude), dms(latitude, true))
        DetailRow(tr("LON.", "DŁUG."), "%.5f".format(longitude), dms(longitude, false))
    }

    accuracyM?.let { DetailRow(tr("ACC.", "BŁĄD"), "%.0f M".format(it), "") }

    // Widoczne kontra użyte w rozwiązaniu: duża różnica oznacza
    // przeszkody terenowe, nawet gdy sam fix wygląda poprawnie.
    DetailRow(
        label = tr("SATS", "SATY"),
        value = "${gnss.usedInFix}/${gnss.visible}",
        trailing = if (gnss.topCn0 > 0f) "%.0f dBHz".format(gnss.topCn0) else "",
        valueColor = if (gnss.hasFix) p.readout else p.amber,
    )
}

@Composable
private fun GsmDetail(cell: SystemStatus.Cellular) {
    val p = Theme.palette

    DetailRow(
        label = tr("NET", "SIEĆ"),
        value = cell.operator ?: "----",
        trailing = "",
        valueColor = if (cell.operator == null) p.amber else p.readout,
    )
    DetailRow(
        label = tr("TYPE", "RODZAJ"),
        value = cell.tech ?: "----",
        trailing = "",
        valueColor = if (cell.tech == null) p.amber else p.readout,
    )
    DetailRow(
        label = tr("SIGNAL", "SYGNAŁ"),
        value = cell.dbm?.let { "$it dBm" } ?: "----",
        trailing = if (cell.level >= 0) "${cell.level}/4" else "",
    )
}

@Composable
private fun PwrDetail(battery: SystemStatus.Battery) {
    val p = Theme.palette
    val t = battery.temperatureC

    DetailRow(
        label = "TEMP",
        value = t?.let { "%.1f\u00B0C".format(it) } ?: "----",
        trailing = when {
            t == null -> ""
            t >= TEMP_CRITICAL -> tr("OVERHEAT", "PRZEGRZANIE")
            t >= TEMP_WARN -> tr("HIGH", "WYSOKA")
            else -> ""
        },
        valueColor = when {
            t == null -> p.amber
            t >= TEMP_CRITICAL -> p.alarm
            t >= TEMP_WARN -> p.amber
            else -> p.readout
        },
    )
    DetailRow(
        label = tr("STATE", "STAN"),
        value = if (battery.charging) tr("CHARGING", "ŁADOWANIE") else tr("ON BATTERY", "NA BATERII"),
        trailing = "",
    )
}

@Composable
private fun DetailRow(
    label: String,
    value: String,
    trailing: String,
    valueColor: Color = Theme.palette.readout,
) {
    val p = Theme.palette

    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Szerokość w znakach, nie w dp: czcionka skaluje się z szerokością
        // ekranu, a stałe 58 dp ucinało "ZASIL." i sklejało je z wartością.
        Label(label.padEnd(7), color = p.dim, softWrap = false)

        Text(
            text = value,
            color = valueColor,
            fontFamily = Type.Readout,
            fontSize = gridSp(Grid.VALUE_SMALL),
        )

        Label(
            trailing,
            color = p.phosphor,
            softWrap = false,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp),
        )
    }
}

/** Stopnie dziesiętne na zapis stopnie-minuty-sekundy z literą półkuli. */
private fun dms(value: Double, isLatitude: Boolean): String {
    val hemisphere = when {
        isLatitude && value >= 0 -> "N"
        isLatitude -> "S"
        value >= 0 -> "E"
        else -> "W"
    }

    val a = abs(value)
    val d = a.toInt()
    val minutesFull = (a - d) * 60
    val m = minutesFull.toInt()
    val s = (minutesFull - m) * 60

    return "%d\u00B0%02d'%04.1f\"%s".format(d, m, s, hemisphere)
}
