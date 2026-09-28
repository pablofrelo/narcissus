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
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import pl.yggdrasil.narcissus2.system.SystemStatus
import pl.yggdrasil.narcissus2.ui.theme.Grid
import pl.yggdrasil.narcissus2.ui.theme.Theme
import pl.yggdrasil.narcissus2.ui.theme.Type
import pl.yggdrasil.narcissus2.ui.theme.gridSp
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
@Composable
fun IndicatorRail(
    status: SystemStatus,
    onTapGnss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = Theme.palette
    val temp = status.battery.temperatureC

    Column(
        modifier.width(68.dp),
        // Rozłożone tak samo jak kolumna odczytów obok — wskaźniki i liczby
        // stoją w tym samym rytmie pionowym, zamiast dryfować względem siebie.
        verticalArrangement = Arrangement.SpaceEvenly,
    ) {
        Indicator(
            tag = "GPS",
            level = status.gnss.level,
            readout = if (status.gnss.hasFix) {
                "${status.gnss.usedInFix}/${status.gnss.visible}"
            } else {
                "----"
            },
            tint = if (status.gnss.hasFix) p.phosphor else p.amber,
            modifier = Modifier.tap(onTapGnss),
        )

        Indicator(
            tag = "GSM",
            level = status.cellular.level.coerceAtLeast(0),
            readout = status.cellular.dbm?.toString() ?: "----",
            tint = when {
                status.cellular.level < 0 -> p.dim
                status.cellular.level <= 1 -> p.amber
                else -> p.phosphor
            },
        )

        // Przegrzanie wypiera procenty. Telefon gotujący się na słońcu
        // w uchwycie to realna awaria: najpierw dławi taktowanie, potem
        // system ubija aplikację. Rozładowanie da się przewidzieć,
        // przegrzanie przychodzi znienacka, więc ma pierwszeństwo.
        val overheating = temp != null && temp >= TEMP_WARN

        Indicator(
            tag = if (overheating) "TMP" else "PWR",
            level = if (overheating) thermalLevel(temp) else status.battery.level,
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
        )
    }
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
) {
    val p = Theme.palette

    Column(modifier.fillMaxWidth()) {
        Label(tag, color = p.dim, softWrap = false)

        Text(
            text = readout,
            color = tint,
            fontFamily = Type.Readout,
            fontSize = gridSp(Grid.UNIT),
            modifier = Modifier.padding(top = 2.dp),
        )

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
 * Panel szczegółów, rozwijany dotknięciem wskaźnika GPS.
 *
 * Trafia tu wszystko, co sprawdzasz sporadycznie i na postoju: pozycja,
 * stan konstelacji, temperatura ogniwa. Na stałym widoku te dane tylko
 * zabierałyby miejsce odczytom, na które patrzysz w ruchu.
 */
@Composable
fun PositionPanel(
    visible: Boolean,
    latitude: Double?,
    longitude: Double?,
    accuracyM: Float?,
    status: SystemStatus,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val p = Theme.palette

    AnimatedVisibility(
        visible = visible,
        enter = expandVertically(),
        exit = shrinkVertically(),
    ) {
        Panel(modifier.tap(onDismiss)) {
            if (latitude == null || longitude == null) {
                DetailRow("SZER.", "BRAK FIXA", "", p.amber)
            } else {
                // Pięć miejsc po przecinku to około metra rozdzielczości.
                // DMS obok, bo mapy papierowe nadal go używają.
                DetailRow("SZER.", "%.5f".format(latitude), dms(latitude, true))
                DetailRow("DŁUG.", "%.5f".format(longitude), dms(longitude, false))
            }

            accuracyM?.let { DetailRow("BŁĄD", "%.0f M".format(it), "") }

            // Widoczne kontra użyte w rozwiązaniu: duża różnica oznacza
            // przeszkody terenowe, nawet gdy sam fix wygląda poprawnie.
            DetailRow(
                label = "SATY",
                value = "${status.gnss.usedInFix}/${status.gnss.visible}",
                trailing = if (status.gnss.topCn0 > 0f) {
                    "%.0f dBHz".format(status.gnss.topCn0)
                } else {
                    ""
                },
                valueColor = if (status.gnss.hasFix) p.readout else p.amber,
            )

            status.battery.temperatureC?.let { t ->
                DetailRow(
                    label = "TEMP",
                    value = "%.1f\u00B0C".format(t),
                    trailing = when {
                        t >= TEMP_CRITICAL -> "PRZEGRZANIE"
                        t >= TEMP_WARN -> "WYSOKA"
                        else -> ""
                    },
                    valueColor = when {
                        t >= TEMP_CRITICAL -> p.alarm
                        t >= TEMP_WARN -> p.amber
                        else -> p.readout
                    },
                )
            }

            DetailRow(
                label = "ZASIL.",
                value = if (status.battery.percent >= 0) "${status.battery.percent}%" else "--",
                trailing = if (status.battery.charging) "ŁADOWANIE" else "DOTKNIJ BY ZWINĄĆ",
            )
        }
    }
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
        Label(label, color = p.dim, modifier = Modifier.width(58.dp), softWrap = false)

        Text(
            text = value,
            color = valueColor,
            fontFamily = Type.Readout,
            fontSize = gridSp(22),
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
